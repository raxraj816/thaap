package com.thaap.payments.fraudlist;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudListEvaluationRecordRepository extends JpaRepository<FraudListEvaluationRecord, UUID> {
}
