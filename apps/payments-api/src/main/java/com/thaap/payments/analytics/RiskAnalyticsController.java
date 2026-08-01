package com.thaap.payments.analytics;

import com.thaap.payments.risk.RiskOperatorContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/risk/analytics")
public class RiskAnalyticsController {

    private final RiskAnalyticsService analyticsService;

    public RiskAnalyticsController(RiskAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/payments")
    public RiskAnalyticsService.PaymentSearchPage searchPayments(
            RiskOperatorContext context,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String fraudAction,
            @RequestParam(required = false) String reviewStatus,
            @RequestParam(required = false) String labelValue) {
        return analyticsService.searchPayments(context, limit, cursor,
                fraudAction, reviewStatus, labelValue);
    }

    @GetMapping("/payments/{id}")
    public RiskAnalyticsService.PaymentDetail paymentDetail(
            RiskOperatorContext context, @PathVariable UUID id) {
        return analyticsService.paymentDetail(context, id);
    }

    @GetMapping("/merchant/summary")
    public RiskAnalyticsService.MerchantSummary merchantSummary(
            RiskOperatorContext context,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "ALL") String originPolicy,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String cursor) {
        return analyticsService.merchantSummary(context, from, to, originPolicy,
                limit, cursor);
    }

    @GetMapping("/merchant/timeseries")
    public RiskAnalyticsService.MerchantTimeSeries merchantTimeSeries(
            RiskOperatorContext context,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "HOUR") String interval,
            @RequestParam(defaultValue = "ALL") String originPolicy,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String cursor) {
        return analyticsService.merchantTimeSeries(context, from, to,
                interval, originPolicy, limit, cursor);
    }

    @GetMapping("/projections")
    public RiskAnalyticsService.ProjectionStatusView projectionStatus(
            RiskOperatorContext context,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String cursor) {
        return analyticsService.projectionStatus(context, limit, cursor);
    }
}
