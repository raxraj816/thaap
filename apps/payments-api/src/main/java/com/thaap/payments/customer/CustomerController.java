package com.thaap.payments.customer;

import com.thaap.payments.tenant.MerchantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

    public CustomerController(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerResponse createCustomer(MerchantContext merchantContext,
            @Valid @RequestBody CreateCustomerRequest request) {
        var customer = customerRepository.save(new Customer(
                merchantContext.merchantId(),
                sha256Lower(request.email()),
                request.phone() == null || request.phone().isBlank() ? null : sha256Lower(request.phone())));
        return CustomerResponse.from(customer);
    }

    private static String sha256Lower(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.toLowerCase().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public record CreateCustomerRequest(@NotBlank @Email String email, String phone) {
    }

    public record CustomerResponse(UUID id, UUID merchantId, String emailHash, String phoneHash) {
        static CustomerResponse from(Customer customer) {
            return new CustomerResponse(customer.getId(), customer.getMerchantId(), customer.getEmailHash(),
                    customer.getPhoneHash());
        }
    }
}
