package com.thaap.payments.investigation;

import com.thaap.payments.risk.RiskOperatorContext;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/risk/workspace/payments")
public class PaymentInvestigationController {

    private final PaymentInvestigationService investigationService;
    private final PaymentTimelineService timelineService;
    private final RelatedPaymentService relatedPaymentService;
    private final PaymentInvestigationSearchService searchService;

    public PaymentInvestigationController(PaymentInvestigationService investigationService,
            PaymentTimelineService timelineService, RelatedPaymentService relatedPaymentService,
            PaymentInvestigationSearchService searchService) {
        this.investigationService = investigationService;
        this.timelineService = timelineService;
        this.relatedPaymentService = relatedPaymentService;
        this.searchService = searchService;
    }

    @Operation(operationId = "searchPaymentInvestigations")
    @PostMapping("/search")
    public PaymentInvestigationSearchService.SearchPage search(RiskOperatorContext context,
            @RequestBody(required = false) SearchRequest request,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer limit,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String cursor) {
        SearchRequest effective = request == null ? new SearchRequest(null, null) : request;
        return searchService.search(context, effective.filter(), effective.sort(), limit, cursor);
    }

    @Operation(operationId = "getPaymentInvestigation")
    @GetMapping("/{paymentIntentId}")
    public PaymentInvestigationService.InvestigationDetail detail(
            RiskOperatorContext context, @PathVariable UUID paymentIntentId) {
        return investigationService.detail(context, paymentIntentId);
    }

    @Operation(operationId = "listPaymentTimeline")
    @GetMapping("/{paymentIntentId}/timeline")
    public PaymentTimelineService.TimelinePage timeline(RiskOperatorContext context,
            @PathVariable UUID paymentIntentId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer limit,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String cursor) {
        return timelineService.timeline(context, paymentIntentId, limit, cursor);
    }

    @Operation(operationId = "listRelatedPayments")
    @GetMapping("/{paymentIntentId}/related")
    public RelatedPaymentService.RelatedPaymentPage related(RiskOperatorContext context,
            @PathVariable UUID paymentIntentId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Instant from,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Instant to,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer limit,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String cursor) {
        return relatedPaymentService.related(context, paymentIntentId, from, to, limit, cursor);
    }

    @Operation(operationId = "listPaymentWebhookActivity")
    @GetMapping("/{paymentIntentId}/webhook-activity")
    public PaymentTimelineService.WebhookActivityPage webhookActivity(RiskOperatorContext context,
            @PathVariable UUID paymentIntentId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer limit,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String cursor) {
        return timelineService.webhookActivity(context, paymentIntentId, limit, cursor);
    }

    public record SearchRequest(PaymentInvestigationFilter filter,
            PaymentInvestigationSort sort) {
    }
}
