package com.thaap.payments.bulk;

import com.thaap.payments.risk.RiskOperatorContext;
import io.swagger.v3.oas.annotations.Operation;
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
@RequestMapping("/risk/workspace/review-bulk-actions")
public class ReviewBulkActionController {

    private final ReviewBulkActionService service;

    public ReviewBulkActionController(ReviewBulkActionService service) {
        this.service = service;
    }

    @Operation(operationId = "startReviewBulkAssignment")
    @PostMapping
    public ResponseEntity<ReviewBulkActionStore.RequestView> start(
            RiskOperatorContext context,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ReviewBulkActionService.StartRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(service.start(context, idempotencyKey, request));
    }

    @Operation(operationId = "getReviewBulkAssignment")
    @GetMapping("/{actionId}")
    public ReviewBulkActionStore.RequestView get(RiskOperatorContext context,
            @PathVariable UUID actionId) {
        return service.get(context, actionId);
    }

    @Operation(operationId = "listReviewBulkAssignmentItems")
    @GetMapping("/{actionId}/items")
    public ReviewBulkActionService.ItemPage items(RiskOperatorContext context,
            @PathVariable UUID actionId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return service.items(context, actionId, limit, cursor);
    }
}
