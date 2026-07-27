package com.thaap.payments.monitoring;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Component
public class MerchantRiskMonitoringRequestMetricsInterceptor
        implements HandlerInterceptor, WebMvcConfigurer {

    private final MerchantRiskMonitoringMetrics metrics;

    public MerchantRiskMonitoringRequestMetricsInterceptor(MerchantRiskMonitoringMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/risk/monitoring/**");
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception exception) {
        metrics.recordRequest(operation(request.getRequestURI()), result(response.getStatus()));
    }

    private String operation(String uri) {
        if (uri.endsWith("/summary")) {
            return "SUMMARY";
        }
        if (uri.contains("/metrics/")) {
            return "METRIC";
        }
        if (uri.endsWith("/taxonomy")) {
            return "TAXONOMY";
        }
        if (uri.endsWith("/snapshots")) {
            return "SNAPSHOT_CREATE";
        }
        if (uri.contains("/snapshots/")) {
            return "SNAPSHOT_READ";
        }
        return "UNKNOWN";
    }

    private String result(int status) {
        if (status >= 200 && status < 300) {
            return "SUCCESS";
        }
        if (status == 400 || status == 405 || status == 415) {
            return "INVALID_REQUEST";
        }
        if (status == 401 || status == 403 || status == 404) {
            return "ACCESS_DENIED";
        }
        if (status == 503) {
            return "QUERY_TIMEOUT_OR_DEPENDENCY";
        }
        return "INTERNAL_FAILURE";
    }
}
