package com.thaap.payments.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.authentication.AuthenticationReplayCipher;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.security.SensitiveValueHasher;
import com.thaap.payments.tenant.MerchantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IdempotencyService {

    private static final Duration DEFAULT_KEY_TTL = Duration.ofHours(24);

    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final SensitiveValueHasher sensitiveValueHasher;
    private final AuthenticationReplayCipher authenticationReplayCipher;
    private final TransactionTemplate operationTransaction;
    private final TransactionTemplate reservationTransaction;
    private final Clock clock;
    private final Duration reservationLease;

    public IdempotencyService(IdempotencyRepository idempotencyRepository, ObjectMapper objectMapper,
            SensitiveValueHasher sensitiveValueHasher, AuthenticationReplayCipher authenticationReplayCipher,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${thaap.idempotency.reservation-lease:30s}") Duration reservationLease) {
        if (reservationLease.isZero() || reservationLease.isNegative()) {
            throw new IllegalArgumentException("Idempotency reservation lease must be positive");
        }
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.sensitiveValueHasher = sensitiveValueHasher;
        this.authenticationReplayCipher = authenticationReplayCipher;
        this.clock = clock;
        this.reservationLease = reservationLease;
        this.operationTransaction = new TransactionTemplate(transactionManager);
        this.reservationTransaction = new TransactionTemplate(transactionManager);
        this.reservationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> ResponseEntity<T> execute(MerchantContext merchantContext, String key, String operation, Object request,
            Class<T> responseType, Supplier<ResponseEntity<T>> operationSupplier) {
        return execute(IdempotencyPrincipal.merchant(merchantContext), key, operation,
                request, responseType, operationSupplier);
    }

    public <T> ResponseEntity<T> execute(RiskOperatorContext operatorContext, String key, String operation,
            Object request, Class<T> responseType, Supplier<ResponseEntity<T>> operationSupplier) {
        return execute(IdempotencyPrincipal.riskOperator(operatorContext), key, operation,
                request, responseType, operationSupplier);
    }

    private <T> ResponseEntity<T> execute(IdempotencyPrincipal principal, String key, String operation,
            Object request, Class<T> responseType, Supplier<ResponseEntity<T>> operationSupplier) {
        if (key == null || key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing Idempotency-Key header");
        }
        if (key.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must contain at most 255 characters");
        }

        String serializedRequest = write(request);
        String currentRequestHash = requestHash(
                serializedRequest, sensitiveValueHasher.currentKeyVersion());
        IdempotencyReservation reservation = reserve(
                principal, key, operation, serializedRequest,
                currentRequestHash, sensitiveValueHasher.currentKeyVersion());
        if (reservation.replayRecord() != null) {
            return replay(reservation.replayRecord(), responseType);
        }

        try {
            return runOperationAndComplete(
                    reservation.recordId(), reservation.leaseToken(), operationSupplier);
        } catch (RuntimeException | Error exception) {
            releaseReservation(reservation.recordId(), reservation.leaseToken());
            throw exception;
        }
    }

    private <T> ResponseEntity<T> replay(IdempotencyRecord record, Class<T> responseType) {
        try {
            String responseBody = unprotectResponse(record);
            T response = objectMapper.readValue(responseBody, responseType);
            return ResponseEntity.status(record.getHttpStatus()).body(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored idempotency response is not readable", exception);
        }
    }

    private IdempotencyReservation reserve(IdempotencyPrincipal principal, String key, String operation,
            String serializedRequest, String currentRequestHash,
            String currentRequestHashKeyVersion) {
        try {
            return reservationTransaction.execute(status -> reserveOnce(
                    principal, key, operation, serializedRequest,
                    currentRequestHash, currentRequestHashKeyVersion));
        } catch (DataIntegrityViolationException exception) {
            return reservationTransaction.execute(status -> handleExisting(
                    principal, key, operation, serializedRequest,
                    currentRequestHash, currentRequestHashKeyVersion));
        }
    }

    private IdempotencyReservation reserveOnce(IdempotencyPrincipal principal, String key, String operation,
            String serializedRequest, String currentRequestHash,
            String currentRequestHashKeyVersion) {
        return findPrincipalRecord(principal, key)
                .map(record -> handleExisting(
                        record, operation, serializedRequest,
                        currentRequestHash, currentRequestHashKeyVersion))
                .orElseGet(() -> {
                    Instant now = clock.instant();
                    UUID leaseToken = UUID.randomUUID();
                    IdempotencyRecord record = new IdempotencyRecord(
                            principal, key, operation, currentRequestHash,
                            currentRequestHashKeyVersion,
                            leaseToken, now.plus(reservationLease),
                            now, now.plus(DEFAULT_KEY_TTL));
                    idempotencyRepository.saveAndFlush(record);
                    return IdempotencyReservation.reserved(record.getId(), leaseToken);
                });
    }

    private IdempotencyReservation handleExisting(IdempotencyPrincipal principal, String key, String operation,
            String serializedRequest, String currentRequestHash,
            String currentRequestHashKeyVersion) {
        IdempotencyRecord record = findPrincipalRecord(principal, key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Idempotency key reservation could not be completed"));
        return handleExisting(record, operation, serializedRequest,
                currentRequestHash, currentRequestHashKeyVersion);
    }

    private java.util.Optional<IdempotencyRecord> findPrincipalRecord(
            IdempotencyPrincipal principal, String key) {
        return idempotencyRepository.findByPrincipalAndKeyForUpdate(
                principal.merchantId(), principal.type(), principal.principalId(),
                principal.credentialPrefix(), key);
    }

    private IdempotencyReservation handleExisting(IdempotencyRecord record, String operation,
            String serializedRequest, String currentRequestHash,
            String currentRequestHashKeyVersion) {
        Instant now = clock.instant();
        if (record.isExpired(now)) {
            UUID leaseToken = UUID.randomUUID();
            record.restart(operation, currentRequestHash, currentRequestHashKeyVersion,
                    leaseToken, now, now.plus(reservationLease),
                    now.plus(DEFAULT_KEY_TTL));
            return IdempotencyReservation.reserved(record.getId(), leaseToken);
        }
        String expectedRequestHash = requestHash(
                serializedRequest, record.getRequestHashKeyVersion());
        if (!record.getOperation().equals(operation)
                || !record.getRequestHash().equals(expectedRequestHash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency key was already used with a different request");
        }
        if (record.getStatus() != IdempotencyStatus.COMPLETED) {
            if (record.hasActiveLease(now)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Idempotency key is already in progress");
            }
            UUID leaseToken = UUID.randomUUID();
            record.reclaim(leaseToken, now, now.plus(reservationLease));
            return IdempotencyReservation.reserved(record.getId(), leaseToken);
        }
        return IdempotencyReservation.replay(record);
    }

    private <T> ResponseEntity<T> runOperationAndComplete(UUID recordId, UUID leaseToken,
            Supplier<ResponseEntity<T>> operationSupplier) {
        return operationTransaction.execute(status -> {
            IdempotencyRecord record = idempotencyRepository.findByIdForUpdate(recordId)
                    .orElseThrow(() -> new IllegalStateException("Idempotency reservation disappeared"));
            record.requireOwnedInProgress(leaseToken);
            ResponseEntity<T> response = operationSupplier.get();
            Instant completedAt = clock.instant();
            String responseBody = protectResponse(record, write(response.getBody()));
            record.complete(leaseToken, response.getStatusCode().value(),
                    responseBody, completedAt,
                    completedAt.plus(DEFAULT_KEY_TTL));
            return response;
        });
    }

    private void releaseReservation(UUID recordId, UUID leaseToken) {
        reservationTransaction.executeWithoutResult(status ->
                idempotencyRepository.findByIdForUpdate(recordId)
                .filter(record -> record.isOwnedInProgress(leaseToken))
                .ifPresent(idempotencyRepository::delete));
    }

    private String requestHash(String serializedRequest, String keyVersion) {
        return sensitiveValueHasher.hmacSha256(
                "idempotency.request", serializedRequest, keyVersion);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Request or response could not be serialized", exception);
        }
    }

    private String protectResponse(IdempotencyRecord record, String responseBody) {
        if (!responseBody.matches("(?s).*\\\"clientSecret\\\"\\s*:\\s*\\\"authc_[A-Za-z0-9_-]{43}\\\".*")) {
            return responseBody;
        }
        AuthenticationReplayCipher.EncryptedReplay replay = authenticationReplayCipher.encrypt(
                responseBody, record.getMerchantId(), record.getId());
        return write(new ProtectedAuthenticationReplay("authentication-replay-v1",
                replay.ciphertext(), replay.nonce(), replay.keyVersion()));
    }

    private String unprotectResponse(IdempotencyRecord record) throws JsonProcessingException {
        String responseBody = record.getResponseBody();
        if (responseBody == null || !responseBody.contains("\"format\":\"authentication-replay-v1\"")) {
            return responseBody;
        }
        ProtectedAuthenticationReplay protectedReplay = objectMapper.readValue(
                responseBody, ProtectedAuthenticationReplay.class);
        return authenticationReplayCipher.decrypt(new AuthenticationReplayCipher.EncryptedReplay(
                        protectedReplay.ciphertext(), protectedReplay.nonce(), protectedReplay.keyVersion()),
                record.getMerchantId(), record.getId());
    }

    private record IdempotencyReservation(
            UUID recordId, UUID leaseToken, IdempotencyRecord replayRecord) {
        static IdempotencyReservation reserved(UUID recordId, UUID leaseToken) {
            return new IdempotencyReservation(recordId, leaseToken, null);
        }

        static IdempotencyReservation replay(IdempotencyRecord record) {
            return new IdempotencyReservation(record.getId(), null, record);
        }
    }

    private record ProtectedAuthenticationReplay(
            String format, String ciphertext, String nonce, String keyVersion) {
    }
}
