package com.thaap.payments.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.support.RiskWorkspaceScenario;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the durable half of C54-CUR-001 on the production engine. An investigation is only
 * defensible if the analyst can say what they saw and when: the stream must be append-only with a
 * gapless per-stream version, a page already handed out must never absorb a later append, and the
 * stored digest must still bind the stored payload.
 */
@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PaymentTimelineAppendPostgreSqlTests {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DomainEventRepository domainEventRepository;

    @Autowired
    private PaymentRiskProjectionConsumerService projectionConsumer;

    @Autowired
    @Qualifier("requestIdFilter")
    private OncePerRequestFilter requestIdFilter;

    @Autowired
    @Qualifier("apiVersionFilter")
    private OncePerRequestFilter apiVersionFilter;

    private RiskWorkspaceScenario scenario;
    private MockMvc mockMvc;
    private RiskWorkspaceScenario.Merchant merchant;
    private RiskWorkspaceScenario.Operator operator;
    private RiskWorkspaceScenario.Payment payment;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @BeforeEach
    void setUp() throws Exception {
        // A shared cached context means a shared database. Without this the fixture
        // inherits the previous class's rows, and drainProjection replays them as if
        // they were its own. See ER-0102.
        databaseCleaner.clean();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate,
                domainEventRepository, projectionConsumer);
        merchant = scenario.merchant("timeline-append-pg");
        operator = scenario.operator(merchant, "timeline-append-pg-key", "RISK_MANAGER");
        payment = scenario.reviewedPayment(merchant, "append-seed");
    }

    @Test
    void twoAppendersRacingForTheSameStreamVersionCannotBothWin() throws Exception {
        long nextVersion = headVersion() + 1;
        Map<String, Object> template = headEventRow();

        try (Connection first = dataSource.getConnection();
                Connection second = dataSource.getConnection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);

            insertEventCopy(first, template, UUID.randomUUID(), nextVersion);
            // The second appender has already read the same head and believes the same version is
            // free. It must block on the unique index rather than write a second event there.
            Thread contender = new Thread(() -> {
                try {
                    insertEventCopy(second, template, UUID.randomUUID(), nextVersion);
                    second.commit();
                } catch (SQLException expected) {
                    // Recorded through the post-condition below rather than swallowed silently.
                }
            });
            contender.start();
            Thread.sleep(300);
            first.commit();
            contender.join(10_000);
            try {
                second.rollback();
            } catch (SQLException ignored) {
                // The contender may already have failed and rolled back.
            }
        }

        Long written = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM domain_events
                WHERE merchant_id = ? AND stream_type = 'PAYMENT' AND stream_id = ?
                  AND stream_version = ?
                """, Long.class, merchant.id(), payment.paymentIntentId(), nextVersion);
        assertThat(written).isEqualTo(1L);
    }

    @Test
    void thePaymentStreamHasNoGapAndNoDuplicateVersion() {
        List<Long> versions = jdbcTemplate.queryForList("""
                SELECT stream_version FROM domain_events
                WHERE merchant_id = ? AND stream_type = 'PAYMENT' AND stream_id = ?
                ORDER BY stream_version
                """, Long.class, merchant.id(), payment.paymentIntentId());

        assertThat(versions).isNotEmpty().doesNotHaveDuplicates();
        assertThat(versions.getFirst()).isEqualTo(1L);
        for (int index = 0; index < versions.size(); index++) {
            assertThat(versions.get(index)).isEqualTo(index + 1L);
        }
    }

    @Test
    void everyStoredPayloadStillHashesToTheDigestRecordedBesideIt() throws Exception {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT payload_bytes, payload_sha256, event_record_bytes, event_record_sha256
                FROM domain_events
                WHERE merchant_id = ? AND stream_type = 'PAYMENT' AND stream_id = ?
                """, merchant.id(), payment.paymentIntentId());

        assertThat(rows).isNotEmpty();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Map<String, Object> row : rows) {
            assertThat(HexFormat.of().formatHex(digest.digest((byte[]) row.get("payload_bytes"))))
                    .isEqualTo(row.get("payload_sha256"));
            assertThat(HexFormat.of()
                    .formatHex(digest.digest((byte[]) row.get("event_record_bytes"))))
                    .isEqualTo(row.get("event_record_sha256"));
        }
    }

    @Test
    void theEngineRefusesAnEventThatWouldBreakStreamOrderOrSchemaVersion() {
        Map<String, Object> template = headEventRow();

        assertThatThrownBy(() -> jdbcTemplate.execute((Connection connection) -> {
            insertEventCopy(connection, template, UUID.randomUUID(), 0L);
            return null;
        })).hasMessageContaining("ck_domain_events_stream_version");

        assertThatThrownBy(() -> jdbcTemplate.execute((Connection connection) -> {
            insertEventCopy(connection, template, UUID.randomUUID(), headVersion());
            return null;
        })).hasMessageContaining("uk_domain_events_stream_version");
    }

    @Test
    void aPageAlreadyHandedOutNeverAbsorbsAnEventAppendedAfterIt() throws Exception {
        RiskWorkspaceScenario.Payment cancellable = scenario.allowedPayment(merchant, "append-live");
        String route = "/risk/workspace/payments/" + cancellable.paymentIntentId() + "/timeline";
        JsonNode first = page(route + "?limit=1");
        long ceiling = first.get("maximumStreamVersion").asLong();
        String cursor = first.get("nextCursor").asText();

        // A genuine business append through the public API, not a hand-written row: the stream
        // must grow underneath a cursor that was already handed out.
        mockMvc.perform(MockMvcRequestBuilders
                        .post("/payment_intents/{id}/cancel", cancellable.paymentIntentId())
                        .header(RiskWorkspaceScenario.API_KEY_HEADER, merchant.apiKey())
                        .header(RiskWorkspaceScenario.IDEMPOTENCY_HEADER, "append-live-cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cancellationReason\":\"REQUESTED_BY_CUSTOMER\"}"))
                .andExpect(MockMvcResultMatchers.status().is2xxSuccessful());
        long appendedVersion = headVersion(cancellable.paymentIntentId());
        assertThat(appendedVersion).isGreaterThan(ceiling);

        List<Long> resumed = new ArrayList<>();
        while (cursor != null && !cursor.isBlank()) {
            JsonNode next = page(route + "?limit=1&cursor=" + encode(cursor));
            assertThat(next.get("maximumStreamVersion").asLong()).isEqualTo(ceiling);
            next.get("data").forEach(entry -> resumed.add(entry.get("streamVersion").asLong()));
            cursor = next.get("nextCursor").isNull() ? null : next.get("nextCursor").asText();
        }
        // The analyst's page set is a closed account of one instant. A later append belongs to the
        // next read, not to a page that was already rendered.
        assertThat(resumed).doesNotContain(appendedVersion);

        JsonNode fresh = page(route + "?limit=50");
        assertThat(fresh.get("maximumStreamVersion").asLong()).isEqualTo(appendedVersion);
        List<Long> freshVersions = new ArrayList<>();
        fresh.get("data").forEach(entry -> freshVersions.add(entry.get("streamVersion").asLong()));
        assertThat(freshVersions).contains(appendedVersion);
    }

    @Test
    void aRowWhoseDatabaseIdentityDisagreesWithItsSignedRecordIsRefusedNotRendered()
            throws Exception {
        String route = "/risk/workspace/payments/" + payment.paymentIntentId() + "/timeline";
        assertThat(page(route + "?limit=50").get("data")).isNotEmpty();

        // Replaying a real event record under a fresh primary key is exactly what an actor with
        // write access to the table would do to forge history. The record carries its own event
        // identity, so the read path detects that the row and the record disagree and refuses the
        // whole timeline rather than presenting a plausible forgery to an analyst.
        long nextVersion = headVersion(payment.paymentIntentId()) + 1;
        Map<String, Object> template = headEventRow();
        try (Connection connection = dataSource.getConnection()) {
            insertEventCopy(connection, template, UUID.randomUUID(), nextVersion);
        }

        // The refusal must also be honest about recovery. History is append-only, so this row can
        // never be repaired and an identical replay can never succeed. Publishing it as a
        // transient 503 would invite an endless client retry and bury a tamper signal under a
        // backoff dashboard.
        mockMvc.perform(get(route + "?limit=50")
                        .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, operator.credential()))
                .andExpect(MockMvcResultMatchers.status().is(500))
                .andExpect(MockMvcResultMatchers.jsonPath("$.error")
                        .value("timeline_evidence_integrity_failed"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.retryable").value(false));

        // The forged row cannot be cleaned up: history is append-only even for a repair. That is
        // the point. This payment's stream stays quarantined for the life of the test merchant.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM domain_events WHERE stream_version = ? AND stream_id = ?",
                nextVersion, payment.paymentIntentId()))
                .hasMessageContaining("domain event history is immutable");
    }

    @Test
    void recordedHistoryCannotBeEditedOrErasedEvenToRepairIt() {
        Map<String, Object> head = headEventRow();

        // A forged row cannot be quietly deleted and a genuine row cannot be quietly rewritten.
        // Repair is a forward append, which is what makes the audit trail worth reading.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM domain_events WHERE merchant_id = ? AND id = ?",
                merchant.id(), head.get("id")))
                .hasMessageContaining("domain event history is immutable");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE domain_events SET occurred_at = NOW() WHERE merchant_id = ? AND id = ?",
                merchant.id(), head.get("id")))
                .hasMessageContaining("domain event history is immutable");
    }

    private void insertEventCopy(Connection connection, Map<String, Object> template, UUID id,
            long streamVersion) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO domain_events (id, merchant_id, stream_type, stream_id, stream_version,
                    event_type, event_version, occurred_at, recorded_at, correlation_id,
                    causation_event_id, request_id, trace_id, actor_type, actor_id, traffic_origin,
                    payload_bytes, payload_sha256, event_record_bytes, event_record_sha256)
                SELECT ?, merchant_id, stream_type, stream_id, ?, event_type, event_version,
                    occurred_at, recorded_at, correlation_id, NULL, request_id, trace_id,
                    actor_type, actor_id, traffic_origin, payload_bytes, payload_sha256,
                    event_record_bytes, event_record_sha256
                FROM domain_events WHERE id = ?
                """)) {
            statement.setObject(1, id);
            statement.setLong(2, streamVersion);
            statement.setObject(3, template.get("id"));
            statement.executeUpdate();
        }
    }

    private Map<String, Object> headEventRow() {
        return jdbcTemplate.queryForMap("""
                SELECT id FROM domain_events
                WHERE merchant_id = ? AND stream_type = 'PAYMENT' AND stream_id = ?
                ORDER BY stream_version DESC LIMIT 1
                """, merchant.id(), payment.paymentIntentId());
    }

    private long headVersion() {
        return headVersion(payment.paymentIntentId());
    }

    private long headVersion(java.util.UUID streamId) {
        Long version = jdbcTemplate.queryForObject("""
                SELECT MAX(stream_version) FROM domain_events
                WHERE merchant_id = ? AND stream_type = 'PAYMENT' AND stream_id = ?
                """, Long.class, merchant.id(), streamId);
        return version == null ? 0L : version;
    }

    private JsonNode page(String route) throws Exception {
        return scenario.json(get(route)
                .header(RiskWorkspaceScenario.RISK_OPERATOR_HEADER, operator.credential()), 200);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
