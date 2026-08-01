package com.thaap.payments.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.thaap.payments.developer.DeveloperScenarioService;
import com.thaap.payments.developer.DeveloperScenarioType;
import com.thaap.payments.event.DomainEventType;
import com.thaap.payments.event.TransactionalDomainEventWriter;
import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.MerchantContext;
import com.thaap.payments.tenant.TrafficOrigin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

@ActiveProfiles("test")
@SpringBootTest
class RiskOperationsDomainEventRollbackTests {

    @Autowired
    private DeveloperScenarioService scenarioService;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @MockitoSpyBean
    private TransactionalDomainEventWriter eventWriter;

    @BeforeEach
    void cleanDatabase() {
        databaseCleaner.clean();
        reset(eventWriterTarget());
    }

    @Test
    void eventFailureRollsBackScenarioSourceAuditStreamAndPublicationTogether() {
        MerchantContext context = syntheticMerchant("risk-event-rollback");
        doThrow(new IllegalStateException("injected risk event persistence failure"))
                .when(eventWriterTarget()).append(argThat(command ->
                        command.eventType() == DomainEventType.DEVELOPER_SCENARIO_CREATED));

        assertThatThrownBy(() -> scenarioService.create(context,
                DeveloperScenarioType.APPROVED_PAYMENT, "rollback-seed", "rollback-request"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected risk event persistence failure");

        assertThat(count("developer_scenario_runs")).isZero();
        assertThat(count("developer_scenario_steps")).isZero();
        assertThat(count("developer_scenario_events")).isZero();
        assertThat(count("audit_logs")).isZero();
        assertThat(count("domain_events")).isZero();
        assertThat(count("event_stream_heads")).isZero();
        assertThat(count("event_publications")).isZero();
    }

    @Test
    void exactScenarioReplayReturnsDurableTruthWithoutASecondCanonicalFact() {
        MerchantContext context = syntheticMerchant("risk-event-replay");

        DeveloperScenarioService.RunView first = scenarioService.create(context,
                DeveloperScenarioType.APPROVED_PAYMENT, "stable-seed", "first-request");
        DeveloperScenarioService.RunView replay = scenarioService.create(context,
                DeveloperScenarioType.APPROVED_PAYMENT, "stable-seed", "replay-request");

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(count("developer_scenario_runs")).isOne();
        assertThat(count("domain_events")).isOne();
        assertThat(count("event_stream_heads")).isOne();
        assertThat(count("event_publications")).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT event_type FROM domain_events
                 WHERE merchant_id = ? AND stream_id = ?
                """, String.class, context.merchantId(), first.id()))
                .isEqualTo("developer_scenario.created");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT current_version FROM event_stream_heads
                 WHERE merchant_id = ? AND stream_id = ?
                """, Long.class, context.merchantId(), first.id())).isOne();
    }

    private MerchantContext syntheticMerchant(String name) {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant(name, "thaap_test_" + name));
        return new MerchantContext(merchant.getId(), merchant.getName(),
                merchant.getApiKeyPrefix(), TrafficOrigin.SYNTHETIC_SCENARIO);
    }

    private long count(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }

    private TransactionalDomainEventWriter eventWriterTarget() {
        return AopTestUtils.getUltimateTargetObject(eventWriter);
    }
}
