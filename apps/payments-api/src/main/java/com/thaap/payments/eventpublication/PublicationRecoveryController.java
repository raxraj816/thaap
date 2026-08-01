package com.thaap.payments.eventpublication;

import com.thaap.payments.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/internal/data/publications")
public class PublicationRecoveryController {

    private static final String OPERATOR_KEY_HEADER = "X-Platform-Operator-Key";

    private final PublicationRecoveryService recoveryService;
    private final java.time.Clock clock;

    public PublicationRecoveryController(PublicationRecoveryService recoveryService,
            java.time.Clock clock) {
        this.recoveryService = recoveryService;
        this.clock = clock;
    }

    @GetMapping("/health")
    public PublicationHealthResponse health(
            @RequestHeader(name = OPERATOR_KEY_HEADER, required = false) String operatorKey,
            HttpServletRequest request) {
        requireLoopback(request);
        PublicationHealthSnapshot snapshot = recoveryService.health(operatorKey);
        long oldestAgeSeconds = snapshot.oldestUnpublishedAt() == null ? 0L
                : Math.max(0L, Duration.between(snapshot.oldestUnpublishedAt(),
                        clock.instant()).toSeconds());
        return new PublicationHealthResponse(snapshot.pendingCount(),
                snapshot.claimedCount(), snapshot.retryingCount(),
                snapshot.blockedCount(), snapshot.publishedCount(),
                oldestAgeSeconds, snapshot.lastAcknowledgedAt(),
                snapshot.lastOutcome() == null ? "NONE" : snapshot.lastOutcome());
    }

    @PostMapping("/{publicationId}/retry")
    public PublicationRecoveryResponse retry(@PathVariable UUID publicationId,
            @RequestHeader(name = OPERATOR_KEY_HEADER, required = false) String operatorKey,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        requireLoopback(request);
        PublicationRecoveryService.RecoveryResult result = recoveryService.retry(
                operatorKey, idempotencyKey, publicationId, requestId(request));
        return new PublicationRecoveryResponse("PENDING", result.replayed());
    }

    private void requireLoopback(HttpServletRequest request) {
        String remoteAddress = request == null ? null : request.getRemoteAddr();
        try {
            if (remoteAddress == null
                    || !InetAddress.getByName(remoteAddress).isLoopbackAddress()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Publication recovery routes require a loopback connection");
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Publication recovery routes require a loopback connection");
        }
    }

    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestId == null ? "unknown" : requestId.toString();
    }

    public record PublicationHealthResponse(long pendingCount, long claimedCount,
            long retryingCount, long blockedCount, long publishedCount,
            long oldestUnpublishedAgeSeconds, Instant lastAcknowledgedAt,
            String lastOutcome) {
    }

    public record PublicationRecoveryResponse(String lifecycleStatus,
            boolean replayed) {
    }
}
