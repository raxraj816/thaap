package com.thaap.payments.customer;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    boolean existsByIdAndMerchantId(UUID id, UUID merchantId);

    Optional<Customer> findByIdAndMerchantId(UUID id, UUID merchantId);
}
