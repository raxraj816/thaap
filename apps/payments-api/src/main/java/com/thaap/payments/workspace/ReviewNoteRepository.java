package com.thaap.payments.workspace;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewNoteRepository extends JpaRepository<ReviewNote, UUID> {

    @Query("""
            SELECT note FROM ReviewNote note
             WHERE note.merchantId = :merchantId
               AND note.manualReviewCaseId = :reviewCaseId
             ORDER BY note.createdAt, note.id
            """)
    List<ReviewNote> findCaseNotes(@Param("merchantId") UUID merchantId,
            @Param("reviewCaseId") UUID reviewCaseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT note FROM ReviewNote note WHERE note.id = :id AND note.merchantId = :merchantId")
    Optional<ReviewNote> findByIdAndMerchantIdForUpdate(@Param("id") UUID id,
            @Param("merchantId") UUID merchantId);

    @Query(value = """
            SELECT * FROM review_notes
             WHERE lifecycle_status = 'ACTIVE' AND retention_expires_at <= :now
             ORDER BY retention_expires_at, id
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ReviewNote> findDueForUpdate(@Param("now") Instant now, @Param("limit") int limit);

    @Query("""
            SELECT DISTINCT note.keyVersion FROM ReviewNote note
             WHERE note.lifecycleStatus = 'ACTIVE'
            """)
    List<String> findRequiredActiveKeyVersions();
}
