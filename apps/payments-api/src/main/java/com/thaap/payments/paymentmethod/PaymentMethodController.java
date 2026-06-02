package com.thaap.payments.paymentmethod;

import com.thaap.payments.customer.CustomerRepository;
import com.thaap.payments.idempotency.IdempotencyService;
import com.thaap.payments.tenant.MerchantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/payment_methods")
public class PaymentMethodController {

    private final PaymentMethodRepository paymentMethodRepository;
    private final CustomerRepository customerRepository;
    private final IdempotencyService idempotencyService;

    public PaymentMethodController(PaymentMethodRepository paymentMethodRepository, CustomerRepository customerRepository,
            IdempotencyService idempotencyService) {
        this.paymentMethodRepository = paymentMethodRepository;
        this.customerRepository = customerRepository;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<PaymentMethodResponse> createPaymentMethod(MerchantContext merchantContext,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentMethodRequest request) {
        return idempotencyService.execute(merchantContext, idempotencyKey, "payment_methods.create", request,
                PaymentMethodResponse.class, () -> createPaymentMethodOnce(merchantContext, request));
    }

    @Transactional
    protected ResponseEntity<PaymentMethodResponse> createPaymentMethodOnce(MerchantContext merchantContext,
            CreatePaymentMethodRequest request) {
        if (!customerRepository.existsByIdAndMerchantId(request.customerId(), merchantContext.merchantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer was not found for merchant");
        }
        var normalizedCard = request.cardNumber().replace(" ", "");
        if (!normalizedCard.matches("[0-9]{12,19}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Card number must contain 12 to 19 digits");
        }
        var paymentMethod = paymentMethodRepository.save(new PaymentMethod(merchantContext.merchantId(),
                request.customerId(), "pm_tok_" + UUID.randomUUID().toString().replace("-", ""),
                sha256(normalizedCard), normalizedCard.substring(0, 6),
                normalizedCard.substring(normalizedCard.length() - 4), cardFunding(normalizedCard), cardCountry()));
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentMethodResponse.from(paymentMethod));
    }

    private static CardFunding cardFunding(String normalizedCard) {
        if (normalizedCard.startsWith("4") || normalizedCard.startsWith("5")) {
            return CardFunding.CREDIT;
        }
        return CardFunding.UNKNOWN;
    }

    private static String cardCountry() {
        return "US";
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public record CreatePaymentMethodRequest(
            @NotNull UUID customerId,
            @NotBlank @Pattern(regexp = "[0-9 ]{12,19}") String cardNumber) {
    }

    public record PaymentMethodResponse(UUID id, UUID merchantId, UUID customerId, String type, String token,
            String cardFingerprint, String cardBin, String cardLast4, CardFunding cardFunding, String cardCountry) {
        static PaymentMethodResponse from(PaymentMethod paymentMethod) {
            return new PaymentMethodResponse(paymentMethod.getId(), paymentMethod.getMerchantId(),
                    paymentMethod.getCustomerId(), paymentMethod.getType(), paymentMethod.getToken(),
                    paymentMethod.getCardFingerprint(), paymentMethod.getCardBin(), paymentMethod.getCardLast4(),
                    paymentMethod.getCardFunding(), paymentMethod.getCardCountry());
        }
    }
}
