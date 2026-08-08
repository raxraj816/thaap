package com.thaap.payments.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.observability.DecisionIntelligenceMetrics;
import com.thaap.payments.time.DatabaseInstant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiVersionFilter extends OncePerRequestFilter {

    public static final String HEADER = "Thaap-Version";
    public static final String CURRENT_VERSION = "2026-08-01";
    public static final String ATTRIBUTE = ApiVersionFilter.class.getName() + ".effectiveVersion";

    private final ObjectMapper objectMapper;
    private final DecisionIntelligenceMetrics metrics;

    public ApiVersionFilter(ObjectMapper objectMapper, DecisionIntelligenceMetrics metrics) {
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/internal/") || path.startsWith("/actuator/")
                || path.startsWith("/admin/") || path.startsWith("/platform/")
                || path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        List<String> values = values(request.getHeaders(HEADER));
        PublicApiErrorDescriptor error = null;
        if (values.size() > 1) {
            error = PublicApiErrorDescriptor.MALFORMED_API_VERSION;
        } else if (!values.isEmpty()) {
            String requested = values.getFirst().trim();
            if (!validDate(requested)) {
                error = PublicApiErrorDescriptor.MALFORMED_API_VERSION;
            } else if (!CURRENT_VERSION.equals(requested)) {
                error = PublicApiErrorDescriptor.UNSUPPORTED_API_VERSION;
            }
        }
        metrics.version(error == null ? values.isEmpty() ? "ABSENT" : "EXACT"
                : error == PublicApiErrorDescriptor.MALFORMED_API_VERSION
                        ? "MALFORMED" : "UNSUPPORTED");
        request.setAttribute(ATTRIBUTE, CURRENT_VERSION);
        response.setHeader(HEADER, CURRENT_VERSION);
        if (error != null) {
            writeError(request, response, error);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean validDate(String value) {
        if (value.length() != 10 || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            return false;
        }
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private List<String> values(Enumeration<String> headers) {
        List<String> values = new ArrayList<>();
        while (headers != null && headers.hasMoreElements()) {
            String header = headers.nextElement();
            if (header == null) continue;
            for (String value : header.split(",", -1)) {
                values.add(value);
            }
        }
        return values;
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response,
            PublicApiErrorDescriptor descriptor) throws IOException {
        response.setStatus(descriptor.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Object rawRequestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        String requestId = rawRequestId == null ? "unknown" : rawRequestId.toString();
        ApiErrorResponse body = new ApiErrorResponse(requestId, DatabaseInstant.now(),
                descriptor.status().value(), descriptor.code(), descriptor.safeMessage(),
                request.getRequestURI(), List.of(), descriptor.retryable(),
                descriptor.recoveryAction());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
