package com.thaap.payments.simulation;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class RiskSimulationActivationGate {

    private final JdbcTemplate jdbcTemplate;
    private final RiskSimulationCandidateSnapshotService snapshotService;
    private final Clock clock;
    private final long minimumEligiblePopulation;
    private final int minimumLabelCoveragePercent;

    public RiskSimulationActivationGate(JdbcTemplate jdbcTemplate,
            RiskSimulationCandidateSnapshotService snapshotService, Clock clock,
            @Value("${thaap.simulation.gate.minimum-eligible-population:100}")
            long minimumEligiblePopulation,
            @Value("${thaap.simulation.gate.minimum-label-coverage-percent:50}")
            int minimumLabelCoveragePercent) {
        this.jdbcTemplate = jdbcTemplate;
        this.snapshotService = snapshotService;
        this.clock = clock;
        this.minimumEligiblePopulation = minimumEligiblePopulation;
        this.minimumLabelCoveragePercent = minimumLabelCoveragePercent;
    }

    @Transactional
    public GateDecision evaluate(UUID merchantId, UUID runId, UUID approvingOperatorId) {
        GateFacts facts = jdbcTemplate.query("""
                SELECT run.lifecycle_status, run.report_expires_at,
                       run.candidate_type, run.candidate_version_id, run.candidate_digest,
                       run.created_by_operator_id, report.eligible_count,
                       report.labelled_count, report.blocking_data_quality_count,
                       report.protected_suppression_count
                  FROM risk_simulation_runs run
             LEFT JOIN risk_simulation_reports report
                    ON report.merchant_id = run.merchant_id
                   AND report.simulation_run_id = run.id
                 WHERE run.merchant_id = ? AND run.id = ?
                """, resultSet -> resultSet.next() ? new GateFacts(
                        RiskSimulationLifecycleStatus.valueOf(resultSet.getString(1)),
                        resultSet.getTimestamp(2) == null ? null : resultSet.getTimestamp(2).toInstant(),
                        RiskSimulationCandidateType.valueOf(resultSet.getString(3)),
                        resultSet.getObject(4, UUID.class), resultSet.getString(5),
                        resultSet.getObject(6, UUID.class), resultSet.getLong(7),
                        resultSet.getLong(8), resultSet.getLong(9), resultSet.getLong(10)) : null,
                merchantId, runId);
        if (facts == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Simulation was not found");
        }
        List<RiskSimulationGateReason> reasons = new ArrayList<>();
        if (facts.status() != RiskSimulationLifecycleStatus.SUCCEEDED) {
            reasons.add(RiskSimulationGateReason.RUN_NOT_SUCCEEDED);
        }
        Instant now = clock.instant();
        if (facts.reportExpiresAt() == null || !now.isBefore(facts.reportExpiresAt())) {
            reasons.add(RiskSimulationGateReason.REPORT_EXPIRED);
        }
        if (facts.status() == RiskSimulationLifecycleStatus.SUCCEEDED) {
            RiskSimulationCandidateSnapshotService.FrozenCandidate current = snapshotService.freeze(
                    merchantId, facts.candidateType(), facts.candidateVersionId());
            if (!current.digest().equals(facts.candidateDigest())) {
                reasons.add(RiskSimulationGateReason.CANDIDATE_DIGEST_MISMATCH);
            }
        }
        if (facts.createdBy().equals(approvingOperatorId)) {
            reasons.add(RiskSimulationGateReason.CREATOR_CANNOT_APPROVE);
        }
        Boolean activeManager = jdbcTemplate.query("""
                SELECT role = 'RISK_MANAGER' AND status = 'ACTIVE'
                  FROM risk_operators WHERE merchant_id = ? AND id = ?
                """, resultSet -> resultSet.next() ? resultSet.getBoolean(1) : null,
                merchantId, approvingOperatorId);
        if (!Boolean.TRUE.equals(activeManager)) {
            reasons.add(RiskSimulationGateReason.APPROVER_NOT_ACTIVE_MANAGER);
        }
        if (facts.eligibleCount() < minimumEligiblePopulation) {
            reasons.add(RiskSimulationGateReason.INSUFFICIENT_ELIGIBLE_POPULATION);
        }
        if (facts.eligibleCount() == 0
                || facts.labelledCount() * 100 < facts.eligibleCount() * minimumLabelCoveragePercent) {
            reasons.add(RiskSimulationGateReason.INSUFFICIENT_LABEL_COVERAGE);
        }
        if (facts.blockingDataQualityCount() != 0) {
            reasons.add(RiskSimulationGateReason.BLOCKING_DATA_QUALITY);
        }
        if (facts.protectedSuppressionCount() != 0) {
            reasons.add(RiskSimulationGateReason.PROTECTED_SUPPRESSION_ATTEMPTED);
        }
        List<RiskSimulationGateReason> immutableReasons = List.copyOf(reasons);
        return new GateDecision(immutableReasons.isEmpty()
                ? RiskSimulationGateDisposition.ACCEPTED : RiskSimulationGateDisposition.REJECTED,
                immutableReasons, facts.candidateDigest(), facts.reportExpiresAt());
    }

    public record GateDecision(RiskSimulationGateDisposition disposition,
            List<RiskSimulationGateReason> reasons, String candidateDigest,
            Instant reportExpiresAt) { }

    private record GateFacts(RiskSimulationLifecycleStatus status, Instant reportExpiresAt,
            RiskSimulationCandidateType candidateType, UUID candidateVersionId,
            String candidateDigest, UUID createdBy, long eligibleCount, long labelledCount,
            long blockingDataQualityCount, long protectedSuppressionCount) { }
}
