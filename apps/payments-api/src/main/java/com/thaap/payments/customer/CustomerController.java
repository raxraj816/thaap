package com.thaap.payments.customer;

import com.thaap.payments.idempotency.IdempotencyService;
import com.thaap.payments.security.CorrelationValueHasher;
import com.thaap.payments.tenant.MerchantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/customers")
public class CustomerController {

    private final CustomerRepository customerRepository;
    private final CorrelationValueHasher correlationValueHasher;
    private final IdempotencyService idempotencyService;

    public CustomerController(CustomerRepository customerRepository,
            CorrelationValueHasher correlationValueHasher,
            IdempotencyService idempotencyService) {
        this.customerRepository = customerRepository;
        this.correlationValueHasher = correlationValueHasher;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> createCustomer(MerchantContext merchantContext,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateCustomerRequest request) {
        if (idempotencyKey == null) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(createCustomerOnce(merchantContext, request));
        }
        return idempotencyService.execute(
                merchantContext, idempotencyKey, "customers.create", request,
                CustomerResponse.class,
                () -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(createCustomerOnce(merchantContext, request)));
    }

    private CustomerResponse createCustomerOnce(MerchantContext merchantContext,
            CreateCustomerRequest request) {
        Customer customer = customerRepository.save(new Customer(
                merchantContext.merchantId(),
                correlationValueHasher.hmacSha256Lowercase("customer.email", request.email()),
                request.phone() == null || request.phone().isBlank()
                        ? null
                        : correlationValueHasher.hmacSha256Lowercase("customer.phone", request.phone())));
        return CustomerResponse.from(customer);
    }

    public record CreateCustomerRequest(@NotBlank @Email String email, String phone) {
    }

    public record CustomerResponse(UUID id, UUID merchantId) {
        static CustomerResponse from(Customer customer) {
            return new CustomerResponse(customer.getId(), customer.getMerchantId());
        }
    }
}
