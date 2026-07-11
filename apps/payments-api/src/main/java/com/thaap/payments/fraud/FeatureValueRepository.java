package com.thaap.payments.fraud;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeatureValueRepository extends JpaRepository<FeatureValue, UUID> {

    List<FeatureValue> findByMerchantIdAndPaymentAttemptIdOrderByFeatureCodeAsc(
            UUID merchantId, UUID paymentAttemptId);

    @Query("""
            select fv from FeatureValue fv, FraudDecision fd
            where fd.id = :decisionId
              and fd.merchantId = :merchantId
              and fv.merchantId = fd.merchantId
              and fv.paymentAttemptId = fd.paymentAttemptId
              and fv.observedAt <= fd.decisionStartedAt
            order by fv.featureCode asc
            """)
    List<FeatureValue> findPointInTimeForDecision(
            @Param("merchantId") UUID merchantId, @Param("decisionId") UUID decisionId);
}
