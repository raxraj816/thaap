package com.thaap.payments.monitoring;

import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.idempotency.IdempotencyService;
import com.thaap.payments.monitoring.MerchantRiskMonitoringQueryService.MetricResult;
import com.thaap.payments.monitoring.MerchantRiskMonitoringQueryService.MonitoringRequest;
import com.thaap.payments.monitoring.MerchantRiskMonitoringQueryService.SummaryView;
import com.thaap.payments.monitoring.MerchantRiskMonitoringQueryService.TaxonomyEntry;
import com.thaap.payments.monitoring.MerchantRiskMonitoringCohortService.CohortPage;
import com.thaap.payments.monitoring.MerchantRiskMonitoringSnapshotService.SnapshotView;
import com.thaap.payments.risk.RiskOperatorContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
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
@RequestMapping("/risk/monitoring")
public class MerchantRiskMonitoringController {

    private final MerchantRiskMonitoringQueryService queryService;
    private final MerchantRiskMonitoringCohortService cohortService;
    private final MerchantRiskMonitoringSnapshotService snapshotService;
    private final MerchantRiskMonitoringMetrics metrics;
    private final IdempotencyService idempotencyService;

    public MerchantRiskMonitoringController(MerchantRiskMonitoringQueryService queryService,
            MerchantRiskMonitoringCohortService cohortService,
            MerchantRiskMonitoringSnapshotService snapshotService,
            MerchantRiskMonitoringMetrics metrics, IdempotencyService idempotencyService) {
        this.queryService = queryService;
        this.cohortService = cohortService;
        this.snapshotService = snapshotService;
        this.metrics = metrics;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping("/summary")
    public SummaryView summary(RiskOperatorContext context,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant windowStart,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant windowEnd,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant asOf,
            @RequestParam(required = false) MonitoringTrafficOriginPolicy trafficOriginPolicy) {
        SummaryView summary = queryService.summary(context,
                new MonitoringRequest(windowStart, windowEnd, asOf, trafficOriginPolicy));
        metrics.recordRead(null, null);
        return summary;
    }

    @GetMapping("/metrics/{metricCode}")
    public MetricResult metric(RiskOperatorContext context,
            @PathVariable MerchantRiskMetricCode metricCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant windowStart,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant windowEnd,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant asOf,
            @RequestParam(required = false) MonitoringTrafficOriginPolicy trafficOriginPolicy) {
        MetricResult result = queryService.metric(context, metricCode,
                new MonitoringRequest(windowStart, windowEnd, asOf, trafficOriginPolicy));
        metrics.recordRead(metricCode, result.coverageStatus());
        return result;
    }

    @GetMapping("/metrics/{metricCode}/cohort")
    public CohortPage cohort(RiskOperatorContext context,
            @PathVariable MerchantRiskMetricCode metricCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant windowStart,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant windowEnd,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant asOf,
            @RequestParam(required = false) MonitoringTrafficOriginPolicy trafficOriginPolicy,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        CohortPage page = cohortService.cohort(context, metricCode,
                new MonitoringRequest(windowStart, windowEnd, asOf, trafficOriginPolicy),
                limit, cursor);
        metrics.recordRead(metricCode, null);
        return page;
    }

    @GetMapping("/taxonomy")
    public List<TaxonomyEntry> taxonomy(RiskOperatorContext context,
            @RequestParam(required = false) FraudTaxonomySourceType sourceType) {
        return queryService.taxonomy(sourceType);
    }

    @PostMapping("/snapshots")
    public ResponseEntity<SnapshotView> createSnapshot(RiskOperatorContext context,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateSnapshotRequest request,
            HttpServletRequest servletRequest) {
        MonitoringRequest monitoringRequest = new MonitoringRequest(request.windowStart(),
                request.windowEnd(), request.asOf(), request.trafficOriginPolicy());
        return idempotencyService.execute(context, idempotencyKey,
                "risk.monitoring.snapshots.create", request, SnapshotView.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(
                        snapshotService.create(context, monitoringRequest, requestId(servletRequest))));
    }

    @GetMapping("/snapshots/{snapshotId}")
    public SnapshotView getSnapshot(RiskOperatorContext context, @PathVariable UUID snapshotId) {
        return snapshotService.get(context, snapshotId);
    }

    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestId == null ? "unknown" : requestId.toString();
    }

    public record CreateSnapshotRequest(
            @NotNull Instant windowStart,
            @NotNull Instant windowEnd,
            @NotNull Instant asOf,
            @NotNull MonitoringTrafficOriginPolicy trafficOriginPolicy) { }
}
