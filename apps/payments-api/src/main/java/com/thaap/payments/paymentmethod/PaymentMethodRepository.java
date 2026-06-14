package com.thaap.payments.paymentmethod;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, UUID> {

    Optional<PaymentMethod> findByIdAndMerchantId(UUID id, UUID merchantId);
}
