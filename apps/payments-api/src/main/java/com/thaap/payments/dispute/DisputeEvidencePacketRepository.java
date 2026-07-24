package com.thaap.payments.dispute;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DisputeEvidencePacketRepository extends JpaRepository<DisputeEvidencePacket, UUID> {
    Optional<DisputeEvidencePacket> findByMerchantIdAndChargebackId(UUID merchantId, UUID chargebackId);
}
