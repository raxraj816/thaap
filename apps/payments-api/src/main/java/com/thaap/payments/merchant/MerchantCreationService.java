package com.thaap.payments.merchant;

import com.thaap.payments.security.SecretHasher;
import com.thaap.payments.security.SensitiveValueHasher;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MerchantCreationService {

    private static final Duration REPLAY_TTL = Duration.ofHours(24);

    private final MerchantRepository merchantRepository;
    private final MerchantCreationRecordRepository creationRecordRepository;
    private final MerchantCreationIdentityRepository creationIdentityRepository;
    private final EntityManager entityManager;
    private final SensitiveValueHasher sensitiveValueHasher;
    private final Clock clock;
    private final Duration reservationLease;
    private final TransactionTemplate creationTransaction;
    private final TransactionTemplate reservationTransaction;

    public MerchantCreationService(MerchantRepository merchantRepository,
            MerchantCreationRecordRepository creationRecordRepository,
            MerchantCreationIdentityRepository creationIdentityRepository,
            EntityManager entityManager, SensitiveValueHasher sensitiveValueHasher, Clock clock,
            PlatformTransactionManager transactionManager,
            @Value("${thaap.idempotency.reservation-lease:30s}") Duration reservationLease) {
        if (reservationLease.isZero() || reservationLease.isNegative()) {
            throw new IllegalArgumentException("Idempotency reservation lease must be positive");
        }
        this.merchantRepository = merchantRepository;
        this.creationRecordRepository = creationRecordRepository;
        this.creationIdentityRepository = creationIdentityRepository;
        this.entityManager = entityManager;
        this.sensitiveValueHasher = sensitiveValueHasher;
        this.clock = clock;
        this.reservationLease = reservationLease;
        this.creationTransaction = new TransactionTemplate(transactionManager);
        this.reservationTransaction = new TransactionTemplate(transactionManager);
        this.reservationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public MerchantCreationResult create(String idempotencyKey, String name) {
        requireIdempotencyKey(idempotencyKey);
        List<SensitiveValueHasher.VersionedDigest> keyIdentities =
                sensitiveValueHasher.hmacSha256Keyring(
                        "merchant.creation.idempotency", idempotencyKey);
        MerchantCreationReservation reservation = reserve(keyIdentities, name);
        String rawApiKey = deriveApiKey(reservation.record());
        if (reservation.replayRecord() != null) {
            return replay(reservation.replayRecord(), rawApiKey);
        }
        try {
            MerchantCreationResult result = creationTransaction.execute(status ->
                    createOnce(name, rawApiKey, reservation.recordId(),
                            reservation.leaseToken()));
            if (result == null) {
                throw new IllegalStateException("Merchant creation transaction returned no result");
            }
            return result;
        } catch (RuntimeException | Error exception) {
            releaseReservation(reservation.recordId(), reservation.leaseToken());
            throw exception;
        }
    }

    public MerchantCreationResult createUnkeyed(String name) {
        String rawApiKey = "thaap_test_" + UUID.randomUUID().toString().replace("-", "");
        Merchant merchant = merchantRepository.save(new Merchant(name, rawApiKey));
        return new MerchantCreationResult(merchant, rawApiKey);
    }

    private MerchantCreationResult createOnce(String name, String rawApiKey,
            UUID recordId, UUID leaseToken) {
        MerchantCreationRecord record = creationRecordRepository.findByIdForUpdate(recordId)
                .orElseThrow(() -> new IllegalStateException(
                        "Merchant creation reservation disappeared"));
        record.requireOwnedInProgress(leaseToken);
        Merchant merchant = merchantRepository.saveAndFlush(new Merchant(name, rawApiKey));
        Instant now = clock.instant();
        record.complete(leaseToken, merchant.getId(), now, now.plus(REPLAY_TTL));
        return new MerchantCreationResult(merchant, rawApiKey);
    }

    private MerchantCreationReservation reserve(
            List<SensitiveValueHasher.VersionedDigest> keyIdentities, String name) {
        try {
            return reservationTransaction.execute(status -> reserveOnce(keyIdentities, name));
        } catch (DataIntegrityViolationException exception) {
            return reservationTransaction.execute(status -> existing(keyIdentities, name));
        }
    }

    private MerchantCreationReservation reserveOnce(
            List<SensitiveValueHasher.VersionedDigest> keyIdentities, String name) {
        MerchantCreationRecord existingRecord = findExistingRecord(keyIdentities);
        if (existingRecord != null) {
            if (existingRecord.isExpired(clock.instant())) {
                deleteExpiredRecord(existingRecord);
            } else {
                return existing(existingRecord, keyIdentities, name);
            }
        }
        Instant now = clock.instant();
        UUID leaseToken = UUID.randomUUID();
        SensitiveValueHasher.VersionedDigest currentIdentity = keyIdentities.getFirst();
        String requestHash = requestHash(name, currentIdentity.keyVersion());
        MerchantCreationRecord record = new MerchantCreationRecord(
                currentIdentity.digest(), requestHash, currentIdentity.keyVersion(),
                leaseToken, now.plus(reservationLease), now, now.plus(REPLAY_TTL));
        creationRecordRepository.saveAndFlush(record);
        creationIdentityRepository.saveAllAndFlush(keyIdentities.stream()
                .map(identity -> new MerchantCreationIdentity(
                        identity.digest(), identity.keyVersion(), record.getId(), now))
                .toList());
        return MerchantCreationReservation.reserved(record, leaseToken);
    }

    private MerchantCreationReservation existing(
            List<SensitiveValueHasher.VersionedDigest> keyIdentities, String name) {
        MerchantCreationRecord record = findExistingRecord(keyIdentities);
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Merchant creation reservation could not be completed");
        }
        if (record.isExpired(clock.instant())) {
            deleteExpiredRecord(record);
            return reserveOnce(keyIdentities, name);
        }
        return existing(record, keyIdentities, name);
    }

    private void deleteExpiredRecord(MerchantCreationRecord record) {
        if (!record.isExpired(clock.instant())) {
            throw new IllegalStateException(
                    "A live merchant creation record cannot be retired");
        }
        creationRecordRepository.delete(record);
        creationRecordRepository.flush();
        entityManager.clear();
    }

    private MerchantCreationReservation existing(
            MerchantCreationRecord record,
            List<SensitiveValueHasher.VersionedDigest> keyIdentities,
            String name) {
        String requestHash = requestHash(name, record.getDerivationKeyVersion());
        if (!MessageDigest.isEqual(
                record.getRequestHash().getBytes(StandardCharsets.UTF_8),
                requestHash.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency key was already used with a different merchant request");
        }
        ensureIdentityAliases(record, keyIdentities);
        if (record.getStatus() == MerchantCreationStatus.COMPLETED) {
            return MerchantCreationReservation.replay(record);
        }
        Instant now = clock.instant();
        if (record.hasActiveLease(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Merchant creation is already in progress");
        }
        UUID leaseToken = UUID.randomUUID();
        record.reclaim(leaseToken, now, now.plus(reservationLease));
        return MerchantCreationReservation.reserved(record, leaseToken);
    }

    private MerchantCreationRecord findExistingRecord(
            List<SensitiveValueHasher.VersionedDigest> keyIdentities) {
        List<String> identityHashes = keyIdentities.stream()
                .map(SensitiveValueHasher.VersionedDigest::digest)
                .toList();
        List<MerchantCreationIdentity> identities =
                creationIdentityRepository.findByIdentityHashInForUpdate(identityHashes);
        if (identities.isEmpty()) {
            return null;
        }
        Set<UUID> recordIds = identities.stream()
                .map(MerchantCreationIdentity::getMerchantCreationRecordId)
                .collect(java.util.stream.Collectors.toSet());
        if (recordIds.size() != 1) {
            throw new IllegalStateException(
                    "Merchant idempotency identities resolve to conflicting creation records");
        }
        return creationRecordRepository.findByIdForUpdate(recordIds.iterator().next())
                .orElseThrow(() -> new IllegalStateException(
                        "Merchant creation identity lost its reservation lineage"));
    }

    private void ensureIdentityAliases(MerchantCreationRecord record,
            List<SensitiveValueHasher.VersionedDigest> keyIdentities) {
        List<MerchantCreationIdentity> existingIdentities =
                creationIdentityRepository.findByIdentityHashInForUpdate(
                        keyIdentities.stream()
                                .map(SensitiveValueHasher.VersionedDigest::digest)
                                .toList());
        Set<String> existingHashes = existingIdentities.stream()
                .map(MerchantCreationIdentity::getIdentityHash)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        for (MerchantCreationIdentity identity : existingIdentities) {
            if (!identity.getMerchantCreationRecordId().equals(record.getId())) {
                throw new IllegalStateException(
                        "Merchant idempotency identity is bound to another creation record");
            }
        }
        Instant now = clock.instant();
        List<MerchantCreationIdentity> missingIdentities = keyIdentities.stream()
                .filter(identity -> !existingHashes.contains(identity.digest()))
                .map(identity -> new MerchantCreationIdentity(
                        identity.digest(), identity.keyVersion(), record.getId(), now))
                .toList();
        if (!missingIdentities.isEmpty()) {
            creationIdentityRepository.saveAllAndFlush(missingIdentities);
        }
    }

    private MerchantCreationResult replay(
            MerchantCreationRecord record, String rawApiKey) {
        if (!record.getExpiresAt().isAfter(clock.instant())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Merchant creation replay window has expired");
        }
        Merchant merchant = merchantRepository.findById(record.getMerchantId())
                .orElseThrow(() -> new IllegalStateException(
                        "Completed merchant creation lost merchant lineage"));
        if (!MessageDigest.isEqual(
                merchant.getApiKeyHash().getBytes(StandardCharsets.UTF_8),
                SecretHasher.sha256(rawApiKey).getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalStateException(
                    "Completed merchant creation API-key lineage does not match");
        }
        return new MerchantCreationResult(merchant, rawApiKey);
    }

    private void releaseReservation(UUID recordId, UUID leaseToken) {
        reservationTransaction.executeWithoutResult(status ->
                creationRecordRepository.findByIdForUpdate(recordId)
                        .filter(record -> record.isOwnedInProgress(leaseToken))
                        .ifPresent(creationRecordRepository::delete));
    }

    private String deriveApiKey(MerchantCreationRecord record) {
        return "thaap_test_" + sensitiveValueHasher.hmacSha256(
                "merchant.api-key.idempotent",
                record.getId() + "\n"
                        + record.getIdempotencyKeyHash() + "\n"
                        + record.getRequestHash(),
                record.getDerivationKeyVersion());
    }

    private String requestHash(String name, String keyVersion) {
        return sensitiveValueHasher.hmacSha256(
                "merchant.creation.request", name, keyVersion);
    }

    private void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Missing Idempotency-Key header");
        }
        if (idempotencyKey.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must contain at most 255 characters");
        }
    }

    public record MerchantCreationResult(Merchant merchant, String rawApiKey) {
    }

    private record MerchantCreationReservation(
            MerchantCreationRecord record, UUID leaseToken,
            MerchantCreationRecord replayRecord) {

        private UUID recordId() {
            return record.getId();
        }

        private static MerchantCreationReservation reserved(
                MerchantCreationRecord record, UUID leaseToken) {
            return new MerchantCreationReservation(record, leaseToken, null);
        }

        private static MerchantCreationReservation replay(
                MerchantCreationRecord record) {
            return new MerchantCreationReservation(record, null, record);
        }
    }
}
