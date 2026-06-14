package com.thaap.payments.customer;

import com.thaap.payments.security.SensitiveValueHasher;
import com.thaap.payments.tenant.MerchantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/customers")
public class CustomerController {

    private final CustomerRepository customerRepository;
    private final SensitiveValueHasher sensitiveValueHasher;

    public CustomerController(CustomerRepository customerRepository, SensitiveValueHasher sensitiveValueHasher) {
        this.customerRepository = customerRepository;
        this.sensitiveValueHasher = sensitiveValueHasher;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerResponse createCustomer(MerchantContext merchantContext,
            @Valid @RequestBody CreateCustomerRequest request) {
        Customer customer = customerRepository.save(new Customer(
                merchantContext.merchantId(),
                sensitiveValueHasher.hmacSha256Lowercase("customer.email", request.email()),
                request.phone() == null || request.phone().isBlank()
                        ? null
                        : sensitiveValueHasher.hmacSha256Lowercase("customer.phone", request.phone())));
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
