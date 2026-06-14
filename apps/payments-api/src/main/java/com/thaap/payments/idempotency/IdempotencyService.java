package com.thaap.payments.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.security.SensitiveValueHasher;
import com.thaap.payments.tenant.MerchantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
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
    private final TransactionTemplate operationTransaction;
    private final TransactionTemplate reservationTransaction;

    public IdempotencyService(IdempotencyRepository idempotencyRepository, ObjectMapper objectMapper,
            SensitiveValueHasher sensitiveValueHasher, PlatformTransactionManager transactionManager) {
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.sensitiveValueHasher = sensitiveValueHasher;
        this.operationTransaction = new TransactionTemplate(transactionManager);
        this.reservationTransaction = new TransactionTemplate(transactionManager);
        this.reservationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> ResponseEntity<T> execute(MerchantContext merchantContext, String key, String operation, Object request,
            Class<T> responseType, Supplier<ResponseEntity<T>> operationSupplier) {
        if (key == null || key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing Idempotency-Key header");
        }

        String requestHash = requestHash(request);
        IdempotencyReservation reservation = reserve(merchantContext.merchantId(), key, operation, requestHash);
        if (reservation.replayRecord() != null) {
            return replay(reservation.replayRecord(), responseType);
        }

        try {
            return runOperationAndComplete(reservation.recordId(), operationSupplier);
        } catch (RuntimeException | Error exception) {
            releaseReservation(reservation.recordId());
            throw exception;
        }
    }

    private <T> ResponseEntity<T> replay(IdempotencyRecord record, Class<T> responseType) {
        try {
            T response = objectMapper.readValue(record.getResponseBody(), responseType);
            return ResponseEntity.status(record.getHttpStatus()).body(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored idempotency response is not readable", exception);
        }
    }

    private IdempotencyReservation reserve(UUID merchantId, String key, String operation, String requestHash) {
        try {
            return reservationTransaction.execute(status -> reserveOnce(merchantId, key, operation, requestHash));
        } catch (DataIntegrityViolationException exception) {
            return reservationTransaction.execute(status -> handleExisting(merchantId, key, operation, requestHash));
        }
    }

    private IdempotencyReservation reserveOnce(UUID merchantId, String key, String operation, String requestHash) {
        return idempotencyRepository.findByMerchantIdAndKey(merchantId, key)
                .map(record -> handleExisting(record, operation, requestHash))
                .orElseGet(() -> {
                    IdempotencyRecord record = new IdempotencyRecord(merchantId, key, operation, requestHash,
                            Instant.now().plus(DEFAULT_KEY_TTL));
                    idempotencyRepository.saveAndFlush(record);
                    return IdempotencyReservation.reserved(record.getId());
                });
    }

    private IdempotencyReservation handleExisting(UUID merchantId, String key, String operation, String requestHash) {
        IdempotencyRecord record = idempotencyRepository.findByMerchantIdAndKey(merchantId, key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Idempotency key reservation could not be completed"));
        return handleExisting(record, operation, requestHash);
    }

    private IdempotencyReservation handleExisting(IdempotencyRecord record, String operation, String requestHash) {
        if (!record.getOperation().equals(operation) || !record.getRequestHash().equals(requestHash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency key was already used with a different request");
        }
        if (record.getStatus() != IdempotencyStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key is already in progress");
        }
        return IdempotencyReservation.replay(record);
    }

    private <T> ResponseEntity<T> runOperationAndComplete(UUID recordId,
            Supplier<ResponseEntity<T>> operationSupplier) {
        return operationTransaction.execute(status -> {
            ResponseEntity<T> response = operationSupplier.get();
            IdempotencyRecord record = idempotencyRepository.findById(recordId)
                    .orElseThrow(() -> new IllegalStateException("Idempotency reservation disappeared"));
            record.complete(response.getStatusCode().value(), write(response.getBody()));
            return response;
        });
    }

    private void releaseReservation(UUID recordId) {
        reservationTransaction.executeWithoutResult(status -> idempotencyRepository.findById(recordId)
                .filter(record -> record.getStatus() == IdempotencyStatus.IN_PROGRESS)
                .ifPresent(idempotencyRepository::delete));
    }

    private String requestHash(Object request) {
        return sensitiveValueHasher.hmacSha256("idempotency.request", write(request));
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Request or response could not be serialized", exception);
        }
    }

    private record IdempotencyReservation(UUID recordId, IdempotencyRecord replayRecord) {
        static IdempotencyReservation reserved(UUID recordId) {
            return new IdempotencyReservation(recordId, null);
        }

        static IdempotencyReservation replay(IdempotencyRecord record) {
            return new IdempotencyReservation(record.getId(), record);
        }
    }
}
