package com.thaap.payments.controls;

import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.idempotency.IdempotencyService;
import com.thaap.payments.risk.RiskOperatorContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

@RestController
@RequestMapping("/risk/controls")
public class GovernedControlController {

    private final GovernedControlService service;
    private final IdempotencyService idempotencyService;

    public GovernedControlController(GovernedControlService service, IdempotencyService idempotencyService) {
        this.service = service;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping("/rules")
    public List<GovernedControlService.RuleView> rules(RiskOperatorContext context) {
        return service.listRules(context);
    }

    @PostMapping("/rules")
    public ResponseEntity<GovernedControlService.RuleView> createRule(RiskOperatorContext context,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CreateRuleRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.execute(context, key, "risk.controls.rules.create", request,
                GovernedControlService.RuleView.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(service.createRule(context,
                        request.code(), request.displayName(), request.candidateAction(), request.expression(),
                        requestId(servletRequest))));
    }

    @PostMapping("/rules/{id}/{transition}")
    public ResponseEntity<GovernedControlService.RuleView> transitionRule(RiskOperatorContext context,
            @PathVariable UUID id, @PathVariable String transition,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody TransitionRequest request, HttpServletRequest servletRequest) {
        ControlLifecycleStatus target = target(transition);
        return idempotencyService.execute(context, key, "risk.controls.rules." + transition + ":" + id,
                request, GovernedControlService.RuleView.class,
                () -> ResponseEntity.ok(service.transitionRule(context, id, target,
                        request.simulationRunId(), requestId(servletRequest))));
    }

    @GetMapping("/exceptions")
    public List<GovernedControlService.ExceptionView> exceptions(RiskOperatorContext context) {
        return service.listExceptions(context);
    }

    @PostMapping("/exceptions")
    public ResponseEntity<GovernedControlService.ExceptionView> createException(RiskOperatorContext context,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CreateExceptionRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.execute(context, key, "risk.controls.exceptions.create", request,
                GovernedControlService.ExceptionView.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(service.createException(context,
                        request.code(), request.sourceRuleVersionId(), request.paymentMethodId(),
                        request.reasonCode(), request.validFrom(), request.expiresAt(),
                        requestId(servletRequest))));
    }

    @PostMapping("/exceptions/{id}/{transition}")
    public ResponseEntity<GovernedControlService.ExceptionView> transitionException(RiskOperatorContext context,
            @PathVariable UUID id, @PathVariable String transition,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody TransitionRequest request, HttpServletRequest servletRequest) {
        ControlLifecycleStatus target = target(transition);
        return idempotencyService.execute(context, key, "risk.controls.exceptions." + transition + ":" + id,
                request, GovernedControlService.ExceptionView.class,
                () -> ResponseEntity.ok(service.transitionException(context, id, target,
                        request.simulationRunId(), requestId(servletRequest))));
    }

    private ControlLifecycleStatus target(String transition) {
        return switch (transition) {
            case "submit" -> ControlLifecycleStatus.SUBMITTED;
            case "approve" -> ControlLifecycleStatus.APPROVED;
            case "activate", "rollback" -> ControlLifecycleStatus.ACTIVE;
            case "retire" -> ControlLifecycleStatus.RETIRED;
            default -> throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Control transition was not found");
        };
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return value == null ? "unknown" : value.toString();
    }

    public record CreateRuleRequest(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{2,63}") String code,
            @NotBlank @Size(max = 128) String displayName,
            @NotNull FraudDecisionAction candidateAction,
            @NotNull Map<String, Object> expression) { }

    public record CreateExceptionRequest(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{2,63}") String code,
            @NotNull UUID sourceRuleVersionId,
            @NotNull UUID paymentMethodId,
            @NotBlank @Pattern(regexp = "TRUSTED_CUSTOMER_REMEDIATION|FALSE_POSITIVE_REMEDIATION")
            String reasonCode,
            @NotNull Instant validFrom,
            @NotNull Instant expiresAt) { }

    public record TransitionRequest(UUID simulationRunId) { }
}
