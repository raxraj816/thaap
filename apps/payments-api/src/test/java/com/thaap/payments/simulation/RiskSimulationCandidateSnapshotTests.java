package com.thaap.payments.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.controls.AuthoredRuleCodec;
import com.thaap.payments.controls.AuthoredRuleSimulationSnapshot;
import com.thaap.payments.fraud.FeatureValueType;
import com.thaap.payments.fraud.FraudDecisionAction;
import com.thaap.payments.riskpolicy.PolicyThresholdOperator;
import com.thaap.payments.security.SecretHasher;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RiskSimulationCandidateSnapshotTests {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final RiskSimulationCandidateSnapshotService service =
            new RiskSimulationCandidateSnapshotService(mock(JdbcTemplate.class), objectMapper);

    @Test
    void exactFrozenBytesRoundTripWithTheirDigest() throws Exception {
        RiskSimulationCandidateSnapshot snapshot = snapshot();
        String bytes = objectMapper.writeValueAsString(snapshot);

        RiskSimulationCandidateSnapshot parsed = service.parseAndVerify(
                bytes, SecretHasher.sha256(bytes));

        assertThat(parsed).isEqualTo(snapshot);
    }

    @Test
    void oneByteMutationAndUnsupportedSchemaAreRejected() throws Exception {
        String bytes = objectMapper.writeValueAsString(snapshot());
        String digest = SecretHasher.sha256(bytes);

        assertThatThrownBy(() -> service.parseAndVerify(bytes + " ", digest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("digest");
        String unsupported = bytes.replace(RiskSimulationCandidateSnapshot.SCHEMA_VERSION,
                "merchant_risk_policy_v2");
        assertThatThrownBy(() -> service.parseAndVerify(unsupported,
                SecretHasher.sha256(unsupported)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema");
    }

    @Test
    void authoredRuleSnapshotBindsItsActionAndRejectsOuterOrInnerDrift() throws Exception {
        AuthoredRuleCodec.EncodedRule rule = new AuthoredRuleCodec(objectMapper).encode(Map.of(
                "feature", "payment.amount_minor", "operator", "GTE", "value", 100L));
        AuthoredRuleSimulationSnapshot snapshot = new AuthoredRuleSimulationSnapshot(
                AuthoredRuleSimulationSnapshot.SCHEMA_VERSION,
                UUID.fromString("10000000-0000-0000-0000-000000000039"),
                rule.canonicalJson(), rule.digest(), FraudDecisionAction.BLOCK);
        String bytes = objectMapper.writeValueAsString(snapshot);

        AuthoredRuleSimulationSnapshot parsed = service.parseAndVerifyAuthoredRule(
                bytes, SecretHasher.sha256(bytes));

        assertThat(parsed.candidateAction()).isEqualTo(FraudDecisionAction.BLOCK);
        String actionDrift = bytes.replace("BLOCK", "REVIEW");
        assertThatThrownBy(() -> service.parseAndVerifyAuthoredRule(
                actionDrift, SecretHasher.sha256(bytes)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("digest");
        String ruleDrift = bytes.replace("100", "101");
        assertThatThrownBy(() -> service.parseAndVerifyAuthoredRule(
                ruleDrift, SecretHasher.sha256(ruleDrift)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Authored rule digest");
    }

    private RiskSimulationCandidateSnapshot snapshot() {
        UUID candidateId = UUID.fromString("10000000-0000-0000-0000-000000000038");
        RiskSimulationCandidateSnapshot.Threshold threshold =
                new RiskSimulationCandidateSnapshot.Threshold("AMOUNT_REVIEW",
                        "payment.amount_minor", "v1", FeatureValueType.LONG,
                        PolicyThresholdOperator.GTE, 100L, null, List.of(),
                        FraudDecisionAction.REVIEW, 100, "USD");
        return new RiskSimulationCandidateSnapshot(
                RiskSimulationCandidateSnapshot.SCHEMA_VERSION,
                RiskSimulationCandidateType.MERCHANT_RISK_POLICY,
                candidateId, "SIMULATION_POLICY", 1, List.of(threshold));
    }
}
