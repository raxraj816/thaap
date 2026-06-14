package com.thaap.payments.paymentattempt;

import com.thaap.payments.tenant.MerchantContext;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/payment_attempts")
public class PaymentAttemptController {

    private final PaymentAttemptRepository paymentAttemptRepository;

    public PaymentAttemptController(PaymentAttemptRepository paymentAttemptRepository) {
        this.paymentAttemptRepository = paymentAttemptRepository;
    }

    @GetMapping("/{id}")
    public PaymentAttemptResponse getPaymentAttempt(MerchantContext merchantContext, @PathVariable UUID id) {
        return paymentAttemptRepository.findByIdAndMerchantId(id, merchantContext.merchantId())
                .map(PaymentAttemptResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PaymentAttempt was not found"));
    }
}
