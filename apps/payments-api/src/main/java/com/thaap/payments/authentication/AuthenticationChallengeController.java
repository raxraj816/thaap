package com.thaap.payments.authentication;

import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.idempotency.IdempotencyService;
import com.thaap.payments.tenant.MerchantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payment_attempts/{paymentAttemptId}/authentication_challenges")
public class AuthenticationChallengeController {

    private final IdempotencyService idempotencyService;
    private final AuthenticationChallengeService challengeService;

    public AuthenticationChallengeController(IdempotencyService idempotencyService,
            AuthenticationChallengeService challengeService) {
        this.idempotencyService = idempotencyService;
        this.challengeService = challengeService;
    }

    @PostMapping("/{challengeId}/complete")
    public ResponseEntity<CompletionResponse> complete(MerchantContext context,
            @PathVariable UUID paymentAttemptId, @PathVariable UUID challengeId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CompletionRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.execute(context, idempotencyKey,
                "authentication_challenges.complete:" + challengeId, request, CompletionResponse.class,
                () -> ResponseEntity.ok(CompletionResponse.from(challengeService.complete(
                        context, paymentAttemptId, challengeId, request.outcome(), request.clientSecret(),
                        requestId(servletRequest)))));
    }

    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestId == null || requestId.toString().isBlank() ? "unknown" : requestId.toString();
    }

    public record CompletionRequest(
            @NotNull AuthenticationChallengeService.CompletionOutcome outcome,
            @NotBlank @Pattern(regexp = "authc_[A-Za-z0-9_-]{43}") String clientSecret) {
    }

    public record CompletionResponse(UUID challengeId, AuthenticationChallengeSource source,
            AuthenticationChallengeStatus status, AuthenticationResultCode resultCode,
            Instant expiresAt, Instant completedAt, UUID authorizationId,
            com.thaap.payments.authorization.AuthorizationStatus authorizationStatus,
            com.thaap.payments.payment.PaymentIntentStatus paymentIntentStatus,
            com.thaap.payments.paymentattempt.PaymentAttemptStatus paymentAttemptStatus) {
        static CompletionResponse from(AuthenticationChallengeService.CompletionResult result) {
            return new CompletionResponse(result.challenge().getId(), result.challenge().getSource(),
                    result.challenge().getStatus(), result.challenge().getResultCode(),
                    result.challenge().getExpiresAt(), result.challenge().getCompletedAt(),
                    result.authorization() == null ? null : result.authorization().getId(),
                    result.authorization() == null ? null : result.authorization().getStatus(),
                    result.paymentIntentStatus(), result.paymentAttemptStatus());
        }
    }
}
