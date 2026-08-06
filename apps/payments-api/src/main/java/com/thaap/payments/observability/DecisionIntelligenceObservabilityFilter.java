package com.thaap.payments.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class DecisionIntelligenceObservabilityFilter extends OncePerRequestFilter {

    private final DecisionIntelligenceMetrics metrics;

    public DecisionIntelligenceObservabilityFilter(DecisionIntelligenceMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return operation(request.getRequestURI()) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String operation = operation(request.getRequestURI());
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            metrics.request(operation, outcome(response.getStatus()), System.nanoTime() - started);
        }
    }

    private String operation(String path) {
        if (path.equals("/risk/workspace/payments/search")) return "INVESTIGATION_SEARCH";
        if (path.matches("/risk/workspace/payments/[^/]+/timeline")) return "TIMELINE";
        if (path.matches("/risk/workspace/payments/[^/]+/related")) return "RELATED_PAYMENTS";
        if (path.matches("/risk/workspace/payments/[^/]+/webhook-activity")) return "WEBHOOK_ACTIVITY";
        if (path.matches("/risk/workspace/payments/[^/]+")) return "INVESTIGATION_DETAIL";
        if (path.startsWith("/risk/workspace/payment-views")) return "SAVED_VIEW";
        if (path.matches("/risk/workspace/review-bulk-actions/[^/]+/items")) return "BULK_ITEMS";
        if (path.startsWith("/risk/workspace/review-bulk-actions")) return "BULK_ACTION";
        if (path.endsWith("/decision-outcomes")) return "DECISION_INSIGHTS";
        if (path.endsWith("/review-operations")) return "REVIEW_INSIGHTS";
        if (path.endsWith("/insights/operators")) return "OPERATOR_INSIGHTS";
        if (path.startsWith("/developer/openapi/")) return "OPENAPI_ARTIFACT";
        if (path.equals("/developer/api-versions") || path.equals("/developer/error-catalog")) {
            return "API_METADATA";
        }
        return null;
    }

    private String outcome(int status) {
        return switch (status) {
            case 200, 201, 202, 204 -> "SUCCEEDED";
            case 400, 405, 415, 422 -> "INVALID";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "DENIED";
            case 404 -> "NOT_FOUND";
            case 409, 412 -> "CONFLICT";
            case 429 -> "RATE_LIMITED";
            case 503, 504 -> "DEPENDENCY_UNAVAILABLE";
            default -> status >= 500 ? "FAILED" : "INVALID";
        };
    }
}
