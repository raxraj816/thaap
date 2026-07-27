package com.thaap.payments.workspace;

import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.idempotency.IdempotencyService;
import com.thaap.payments.risk.RiskOperatorContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.QueryTimeoutException;
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
@RequestMapping("/risk/workspace")
public class AnalystWorkspaceController {

    private final AnalystQueueService queueService;
    private final ReviewSavedSearchService savedSearchService;
    private final ReviewNoteService noteService;
    private final IdempotencyService idempotencyService;
    private final AnalystWorkspaceMetrics metrics;

    public AnalystWorkspaceController(AnalystQueueService queueService,
            ReviewSavedSearchService savedSearchService, ReviewNoteService noteService,
            IdempotencyService idempotencyService, AnalystWorkspaceMetrics metrics) {
        this.queueService = queueService;
        this.savedSearchService = savedSearchService;
        this.noteService = noteService;
        this.idempotencyService = idempotencyService;
        this.metrics = metrics;
    }

    @PostMapping("/reviews/search")
    public AnalystQueueService.ReviewQueuePage search(RiskOperatorContext context,
            @RequestBody(required = false) QueueSearchRequest request,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        QueueSearchRequest effective = request == null
                ? new QueueSearchRequest(null, null) : request;
        return observed("QUEUE_SEARCH", () -> queueService.search(
                context, effective.filter(), effective.sort(), limit, cursor));
    }

    @GetMapping("/saved-searches")
    public List<ReviewSavedSearchService.SavedSearchView> savedSearches(RiskOperatorContext context) {
        return observed("SAVED_SEARCH_LIST", () -> savedSearchService.list(context));
    }

    @PostMapping("/saved-searches")
    public ResponseEntity<ReviewSavedSearchService.SavedSearchView> createSavedSearch(
            RiskOperatorContext context,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody SaveSearchRequest request, HttpServletRequest servletRequest) {
        return observed("SAVED_SEARCH_CREATE", () -> idempotencyService.execute(context,
                idempotencyKey, "risk.workspace.saved_search.create",
                request, ReviewSavedSearchService.SavedSearchView.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(savedSearchService.create(context,
                        request.name(), request.visibility(), request.filter(), request.sort(),
                        request.visibleColumns(), requestId(servletRequest)))));
    }

    @PostMapping("/saved-searches/{id}")
    public ResponseEntity<ReviewSavedSearchService.SavedSearchView> updateSavedSearch(
            RiskOperatorContext context, @PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody UpdateSavedSearchRequest request, HttpServletRequest servletRequest) {
        return observed("SAVED_SEARCH_UPDATE", () -> idempotencyService.execute(context, idempotencyKey,
                "risk.workspace.saved_search.update:" + id, request,
                ReviewSavedSearchService.SavedSearchView.class,
                () -> ResponseEntity.ok(savedSearchService.update(context, id,
                        request.expectedVersion(), request.name(), request.visibility(),
                        request.filter(), request.sort(), request.visibleColumns(),
                        requestId(servletRequest)))));
    }

    @PostMapping("/saved-searches/{id}/disable")
    public ResponseEntity<ReviewSavedSearchService.SavedSearchView> disableSavedSearch(
            RiskOperatorContext context, @PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody DisableSavedSearchRequest request, HttpServletRequest servletRequest) {
        return observed("SAVED_SEARCH_DISABLE", () -> idempotencyService.execute(context, idempotencyKey,
                "risk.workspace.saved_search.disable:" + id, request,
                ReviewSavedSearchService.SavedSearchView.class,
                () -> ResponseEntity.ok(savedSearchService.disable(context, id,
                        request.expectedVersion(), requestId(servletRequest)))));
    }

    @PostMapping("/saved-searches/{id}/execute")
    public AnalystQueueService.ReviewQueuePage executeSavedSearch(RiskOperatorContext context,
            @PathVariable UUID id, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return observed("SAVED_SEARCH_EXECUTE",
                () -> savedSearchService.execute(context, id, limit, cursor));
    }

    @GetMapping("/reviews/{reviewCaseId}/notes")
    public List<ReviewNoteService.ReviewNoteView> notes(RiskOperatorContext context,
            @PathVariable UUID reviewCaseId,
            @RequestParam long expectedAssignmentVersion, HttpServletRequest servletRequest) {
        return noteService.list(context, reviewCaseId, expectedAssignmentVersion,
                requestId(servletRequest));
    }

    @PostMapping("/reviews/{reviewCaseId}/notes")
    public ResponseEntity<ReviewNoteService.ReviewNoteView> createNote(
            RiskOperatorContext context, @PathVariable UUID reviewCaseId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateNoteRequest request, HttpServletRequest servletRequest) {
        return idempotencyService.executeAuthorized(context, idempotencyKey,
                "risk.workspace.review_note.create:" + reviewCaseId, request,
                ReviewNoteService.ReviewNoteView.class,
                () -> noteService.authorizeCaseForReplay(context, reviewCaseId,
                        request.expectedAssignmentVersion()),
                () -> ResponseEntity.status(HttpStatus.CREATED).body(noteService.create(context,
                        reviewCaseId, request.expectedAssignmentVersion(), request.content(),
                        request.supersedesNoteId(), requestId(servletRequest))));
    }

    @PostMapping("/reviews/{reviewCaseId}/notes/{noteId}/redact")
    public ResponseEntity<ReviewNoteService.ReviewNoteView> redactNote(
            RiskOperatorContext context, @PathVariable UUID reviewCaseId, @PathVariable UUID noteId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) RedactNoteRequest request,
            HttpServletRequest servletRequest) {
        RedactNoteRequest effective = request == null ? new RedactNoteRequest() : request;
        return idempotencyService.executeAuthorized(context, idempotencyKey,
                "risk.workspace.review_note.redact:" + noteId, effective,
                ReviewNoteService.ReviewNoteView.class,
                () -> noteService.authorizeRedactionForReplay(context, reviewCaseId, noteId),
                () -> ResponseEntity.ok(noteService.redact(context, reviewCaseId, noteId,
                        requestId(servletRequest))));
    }

    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestId == null ? "unknown" : requestId.toString();
    }

    private <T> T observed(String operation, Supplier<T> action) {
        try {
            T result = action.get();
            metrics.increment(operation, "SUCCEEDED");
            return result;
        } catch (SavedSearchUpgradeRequiredException exception) {
            metrics.increment(operation, "UPGRADE_REQUIRED");
            throw exception;
        } catch (QueryTimeoutException exception) {
            metrics.increment(operation, "TIMEOUT");
            throw exception;
        } catch (org.springframework.web.server.ResponseStatusException exception) {
            int status = exception.getStatusCode().value();
            metrics.increment(operation, status == 400 ? "INVALID"
                    : status == 409 ? "CONFLICT" : "DENIED");
            throw exception;
        } catch (RuntimeException exception) {
            metrics.increment(operation, "FAILED");
            throw exception;
        }
    }

    public record QueueSearchRequest(ReviewQueueFilter filter, ReviewQueueSort sort) {
    }

    public record SaveSearchRequest(String name, SavedSearchVisibility visibility,
            ReviewQueueFilter filter, ReviewQueueSort sort, Set<ReviewQueueColumn> visibleColumns) {
    }

    public record UpdateSavedSearchRequest(long expectedVersion, String name,
            SavedSearchVisibility visibility, ReviewQueueFilter filter,
            ReviewQueueSort sort, Set<ReviewQueueColumn> visibleColumns) {
    }

    public record DisableSavedSearchRequest(long expectedVersion) {
    }

    public record CreateNoteRequest(long expectedAssignmentVersion, String content,
            UUID supersedesNoteId) {
    }

    public record RedactNoteRequest() {
    }
}
