package com.thaap.payments.investigation;

import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.idempotency.IdempotencyService;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.workspace.SavedSearchVisibility;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/risk/workspace/payment-views")
public class PaymentInvestigationSavedViewController {

    private final PaymentInvestigationSavedViewService service;
    private final IdempotencyService idempotencyService;

    public PaymentInvestigationSavedViewController(
            PaymentInvestigationSavedViewService service,
            IdempotencyService idempotencyService) {
        this.service = service;
        this.idempotencyService = idempotencyService;
    }

    @Operation(operationId = "listPaymentInvestigationSavedViews")
    @GetMapping
    public List<PaymentInvestigationSavedViewService.SavedView> list(
            RiskOperatorContext context) {
        return service.list(context);
    }

    @Operation(operationId = "createPaymentInvestigationSavedView")
    @PostMapping
    public ResponseEntity<PaymentInvestigationSavedViewService.SavedView> create(
            RiskOperatorContext context,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody SaveRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.execute(context, idempotencyKey,
                "risk.workspace.payment_view.create", request,
                PaymentInvestigationSavedViewService.SavedView.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(service.create(context,
                        request.name(), request.visibility(), request.filter(), request.sort(),
                        request.visibleColumns(), requestId(servletRequest))));
    }

    @Operation(operationId = "updatePaymentInvestigationSavedView")
    @PostMapping("/{viewId}")
    public ResponseEntity<PaymentInvestigationSavedViewService.SavedView> update(
            RiskOperatorContext context, @PathVariable UUID viewId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody UpdateRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.execute(context, idempotencyKey,
                "risk.workspace.payment_view.update:" + viewId, request,
                PaymentInvestigationSavedViewService.SavedView.class,
                () -> ResponseEntity.ok(service.update(context, viewId,
                        request.expectedVersion(), request.name(), request.visibility(),
                        request.filter(), request.sort(), request.visibleColumns(),
                        requestId(servletRequest))));
    }

    @Operation(operationId = "disablePaymentInvestigationSavedView")
    @PostMapping("/{viewId}/disable")
    public ResponseEntity<PaymentInvestigationSavedViewService.SavedView> disable(
            RiskOperatorContext context, @PathVariable UUID viewId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody DisableRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.execute(context, idempotencyKey,
                "risk.workspace.payment_view.disable:" + viewId, request,
                PaymentInvestigationSavedViewService.SavedView.class,
                () -> ResponseEntity.ok(service.disable(context, viewId,
                        request.expectedVersion(), requestId(servletRequest))));
    }

    @Operation(operationId = "executePaymentInvestigationSavedView")
    @PostMapping("/{viewId}/execute")
    public PaymentInvestigationSearchService.SearchPage execute(
            RiskOperatorContext context, @PathVariable UUID viewId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return service.execute(context, viewId, limit, cursor);
    }

    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestId == null ? "unknown" : requestId.toString();
    }

    public record SaveRequest(String name, SavedSearchVisibility visibility,
            PaymentInvestigationFilter filter, PaymentInvestigationSort sort,
            Set<PaymentInvestigationColumn> visibleColumns) {
    }

    public record UpdateRequest(long expectedVersion, String name,
            SavedSearchVisibility visibility, PaymentInvestigationFilter filter,
            PaymentInvestigationSort sort, Set<PaymentInvestigationColumn> visibleColumns) {
    }

    public record DisableRequest(long expectedVersion) {
    }
}

