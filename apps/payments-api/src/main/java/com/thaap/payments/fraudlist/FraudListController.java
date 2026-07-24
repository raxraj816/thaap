package com.thaap.payments.fraudlist;

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
@RequestMapping("/risk/fraud-lists")
public class FraudListController {

    private final FraudListManagementService managementService;
    private final IdempotencyService idempotencyService;

    public FraudListController(FraudListManagementService managementService,
            IdempotencyService idempotencyService) {
        this.managementService = managementService;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping
    public List<FraudListManagementService.ListView> list(RiskOperatorContext context) {
        return managementService.list(context);
    }

    @PostMapping
    public ResponseEntity<FraudListManagementService.ListView> create(RiskOperatorContext context,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateListRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.execute(context, idempotencyKey, "risk.fraud-lists.create",
                request, FraudListManagementService.ListView.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(managementService.create(context,
                        request.code(), request.displayName(), request.identityType(), request.genericNamespace(),
                        requestId(servletRequest))));
    }

    @PostMapping("/{listId}/versions")
    public ResponseEntity<FraudListManagementService.ListView> createVersion(RiskOperatorContext context,
            @PathVariable UUID listId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody EmptyRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.execute(context, idempotencyKey,
                "risk.fraud-lists.version.create:" + listId, request,
                FraudListManagementService.ListView.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(
                        managementService.createNextVersion(context, listId, requestId(servletRequest))));
    }

    @PostMapping("/{listId}/entries")
    public ResponseEntity<FraudListManagementService.EntryView> addEntry(RiskOperatorContext context,
            @PathVariable UUID listId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateEntryRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.execute(context, idempotencyKey,
                "risk.fraud-lists.entry.create:" + listId, request,
                FraudListManagementService.EntryView.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(managementService.addEntry(context, listId,
                        request.value(), request.action(), request.reasonCode(), request.effectiveFrom(),
                        request.effectiveUntil(), requestId(servletRequest))));
    }

    @PostMapping("/{listId}/entries/{entryId}/deactivate")
    public ResponseEntity<FraudListManagementService.EntryView> deactivateEntry(RiskOperatorContext context,
            @PathVariable UUID listId, @PathVariable UUID entryId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody EmptyRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.execute(context, idempotencyKey,
                "risk.fraud-lists.entry.deactivate:" + entryId, request,
                FraudListManagementService.EntryView.class,
                () -> ResponseEntity.ok(managementService.deactivateEntry(
                        context, listId, entryId, requestId(servletRequest))));
    }

    @PostMapping("/{listId}/deactivate")
    public ResponseEntity<FraudListManagementService.ListView> deactivateVersion(RiskOperatorContext context,
            @PathVariable UUID listId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody EmptyRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.execute(context, idempotencyKey,
                "risk.fraud-lists.version.deactivate:" + listId, request,
                FraudListManagementService.ListView.class,
                () -> ResponseEntity.ok(managementService.deactivateVersion(
                        context, listId, requestId(servletRequest))));
    }

    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestId == null ? "unknown" : requestId.toString();
    }

    public record CreateListRequest(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{2,63}") String code,
            @NotBlank @Size(max = 128) String displayName,
            @NotNull FraudListIdentityType identityType,
            FraudListGenericNamespace genericNamespace) { }

    public record CreateEntryRequest(
            @NotBlank @Size(max = 320) String value,
            @NotNull FraudDecisionAction action,
            @NotNull FraudListReasonCode reasonCode,
            Instant effectiveFrom,
            Instant effectiveUntil) { }

    public record EmptyRequest() { }
}
