package com.thaap.payments.insights;

import com.thaap.payments.risk.RiskOperatorContext;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/risk/workspace/insights")
public class WorkspaceInsightsController {

    private final WorkspaceInsightsService service;

    public WorkspaceInsightsController(WorkspaceInsightsService service) {
        this.service = service;
    }

    @Operation(operationId = "getDecisionOutcomeInsights")
    @GetMapping("/decision-outcomes")
    public WorkspaceInsightsService.DecisionOutcomeInsights decisionOutcomes(
            RiskOperatorContext context,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "ALL") String originPolicy) {
        return service.decisionOutcomes(context, from, to, originPolicy);
    }

    @Operation(operationId = "getReviewOperationInsights")
    @GetMapping("/review-operations")
    public WorkspaceInsightsService.ReviewOperationsInsights reviewOperations(
            RiskOperatorContext context,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return service.reviewOperations(context, from, to);
    }

    @Operation(operationId = "getOperatorReviewInsights")
    @GetMapping("/operators")
    public WorkspaceInsightsService.OperatorInsights operators(
            RiskOperatorContext context,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return service.operators(context, from, to);
    }
}

