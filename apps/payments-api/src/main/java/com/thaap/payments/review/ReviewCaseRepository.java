package com.thaap.payments.review;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewCaseRepository extends JpaRepository<ReviewCase, UUID> {

    List<ReviewCase> findByMerchantIdOrderByCreatedAtAsc(UUID merchantId);
}
