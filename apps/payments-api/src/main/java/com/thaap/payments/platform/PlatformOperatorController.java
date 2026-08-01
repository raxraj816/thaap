package com.thaap.payments.platform;

import com.thaap.payments.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.InetAddress;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/platform/operators")
public class PlatformOperatorController {

    private static final String OPERATOR_KEY_HEADER = "X-Platform-Operator-Key";
    private static final String BOOTSTRAP_TOKEN_HEADER =
            "X-Thaap-Platform-Bootstrap-Token";

    private final PlatformOperatorService operatorService;

    public PlatformOperatorController(PlatformOperatorService operatorService) {
        this.operatorService = operatorService;
    }

    @PostMapping("/bootstrap")
    public ResponseEntity<PlatformOperatorCredentialResponse> bootstrap(
            @RequestHeader(name = BOOTSTRAP_TOKEN_HEADER, required = false) String bootstrapToken,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody BootstrapPlatformOperatorRequest request,
            HttpServletRequest servletRequest) {
        requireLoopback(servletRequest);
        PlatformOperatorService.MutationResult result = operatorService.bootstrap(
                bootstrapToken, idempotencyKey, request.displayName(),
                requestId(servletRequest));
        return credentialResponse(result);
    }

    @GetMapping("/me")
    public PlatformOperatorSessionResponse session(
            @RequestHeader(name = OPERATOR_KEY_HEADER, required = false) String operatorKey,
            HttpServletRequest servletRequest) {
        requireLoopback(servletRequest);
        PlatformOperatorContext context = operatorService.session(operatorKey);
        return new PlatformOperatorSessionResponse(context.operatorId(),
                context.operatorCode(), context.role(), PlatformOperatorStatus.ACTIVE);
    }

    @PostMapping
    public ResponseEntity<PlatformOperatorCredentialResponse> create(
            @RequestHeader(name = OPERATOR_KEY_HEADER, required = false) String operatorKey,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreatePlatformOperatorRequest request,
            HttpServletRequest servletRequest) {
        requireLoopback(servletRequest);
        PlatformOperatorService.MutationResult result = operatorService.create(
                operatorKey, idempotencyKey, request.displayName(), request.role(),
                requestId(servletRequest));
        return credentialResponse(result);
    }

    @PostMapping("/{operatorId}/disable")
    public ResponseEntity<PlatformOperatorMutationResponse> disable(
            @PathVariable UUID operatorId,
            @RequestHeader(name = OPERATOR_KEY_HEADER, required = false) String operatorKey,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        requireLoopback(servletRequest);
        PlatformOperatorService.MutationResult result = operatorService.disable(
                operatorKey, idempotencyKey, operatorId, requestId(servletRequest));
        return ResponseEntity.ok(PlatformOperatorMutationResponse.from(result));
    }

    private ResponseEntity<PlatformOperatorCredentialResponse> credentialResponse(
            PlatformOperatorService.MutationResult result) {
        PlatformOperatorCredentialResponse body =
                PlatformOperatorCredentialResponse.from(result);
        return result.replayed()
                ? ResponseEntity.ok(body)
                : ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    private void requireLoopback(HttpServletRequest request) {
        String remoteAddress = request == null ? null : request.getRemoteAddr();
        try {
            if (remoteAddress == null
                    || !InetAddress.getByName(remoteAddress).isLoopbackAddress()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Platform operator routes require a loopback connection");
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Platform operator routes require a loopback connection");
        }
    }

    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestId == null ? "unknown" : requestId.toString();
    }

    public record BootstrapPlatformOperatorRequest(
            @NotBlank @Size(max = 120) String displayName) {
    }

    public record CreatePlatformOperatorRequest(
            @NotBlank @Size(max = 120) String displayName,
            @NotNull PlatformOperatorRole role) {
    }

    public record PlatformOperatorSessionResponse(UUID id, String operatorCode,
            PlatformOperatorRole role, PlatformOperatorStatus status) {
    }

    public record PlatformOperatorCredentialResponse(UUID id, String operatorCode,
            PlatformOperatorRole role, PlatformOperatorStatus status,
            String credentialPrefix, String credential,
            boolean credentialRevealed, boolean replayed) {

        static PlatformOperatorCredentialResponse from(
                PlatformOperatorService.MutationResult result) {
            PlatformOperator operator = result.operator();
            return new PlatformOperatorCredentialResponse(operator.getId(),
                    operator.getOperatorCode(), operator.getRole(), operator.getStatus(),
                    result.credentialPrefix(), result.credential(),
                    result.credentialRevealed(), result.replayed());
        }
    }

    public record PlatformOperatorMutationResponse(UUID id, String operatorCode,
            PlatformOperatorRole role, PlatformOperatorStatus status,
            long lifecycleEpoch, Instant createdAt, Instant disabledAt,
            boolean replayed) {

        static PlatformOperatorMutationResponse from(
                PlatformOperatorService.MutationResult result) {
            PlatformOperator operator = result.operator();
            return new PlatformOperatorMutationResponse(operator.getId(),
                    operator.getOperatorCode(), operator.getRole(), operator.getStatus(),
                    operator.getLifecycleEpoch(), operator.getCreatedAt(),
                    operator.getDisabledAt(), result.replayed());
        }
    }
}
