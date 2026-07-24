package com.thaap.payments.velocity;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VelocityObservationRecordRepository extends JpaRepository<VelocityObservationRecord, UUID> {
}
