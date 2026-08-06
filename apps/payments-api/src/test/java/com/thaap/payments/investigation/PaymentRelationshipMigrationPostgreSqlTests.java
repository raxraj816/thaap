package com.thaap.payments.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.event.DomainEventRepository;
import com.thaap.payments.projection.PaymentRiskProjectionConsumerService;
import com.thaap.payments.support.RiskWorkspaceScenario;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves C54-MIG-001 on the production engine. The V86 schema and the V87 protection migration
 * are the only things that keep a relationship edge honest when something other than the writer
 * touches the table: a backfill, an operator, a future service, or a defect. Application code
 * cannot certify them, so every claim here is made against real PostgreSQL DDL.
 */
@ActiveProfiles("test")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PaymentRelationshipMigrationPostgreSqlTests {

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
    private DomainEventRepository domainEventRepository;

    @Autowired
    private PaymentRiskProjectionConsumerService projectionConsumer;

    @Autowired
    private Clock clock;

    @Autowired
    @Qualifier("requestIdFilter")
    private OncePerRequestFilter requestIdFilter;

    @Autowired
    @Qualifier("apiVersionFilter")
    private OncePerRequestFilter apiVersionFilter;

    private RiskWorkspaceScenario scenario;
    private RiskWorkspaceScenario.Merchant merchant;
    private RiskWorkspaceScenario.Payment payment;
    private Map<String, Object> anchor;

    @BeforeEach
    void setUp() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(requestIdFilter, apiVersionFilter).build();
        scenario = new RiskWorkspaceScenario(mockMvc, objectMapper, jdbcTemplate,
                domainEventRepository, projectionConsumer);
        merchant = scenario.merchant("relationship-migration");
        payment = scenario.allowedPayment(merchant, "migration-seed");
        anchor = jdbcTemplate.queryForMap("""
                SELECT id, merchant_id, payment_intent_id, payment_attempt_id, relation_type,
                       relation_identity_hmac, identity_key_version, observed_at, traffic_origin,
                       source_type, source_id
                FROM payment_investigation_relation_anchors
                WHERE merchant_id = ? AND payment_intent_id = ?
                  AND relation_type = 'SAME_CARD_FINGERPRINT'
                """, merchant.id(), payment.paymentIntentId());
    }

    @Test
    void anEstablishedRelationshipEdgeCannotBeRewrittenOrErased() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE payment_investigation_relation_anchors
                SET relation_identity_hmac = ? WHERE id = ?
                """, "0".repeat(64), anchor.get("id")))
                .hasMessageContaining("Investigation relation anchors are append-only");

        // Deleting an edge would quietly shrink a fraud ring after an analyst had already acted on
        // it, leaving the decision unexplainable.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM payment_investigation_relation_anchors WHERE id = ?",
                anchor.get("id")))
                .hasMessageContaining("Investigation relation anchors are append-only");
    }

    @Test
    void anEdgeWhoseDigestDoesNotMatchItsNamedSourceIsRejectedByTheEngine() {
        // The single most damaging forgery is a well-formed anchor pointing at a real payment
        // method with someone else's digest: it fabricates a relationship that survives review.
        assertThatThrownBy(() -> insertAnchor("SAME_CARD_FINGERPRINT", "f".repeat(64),
                "correlation-v1", "PAYMENT_METHOD", (UUID) anchor.get("source_id")))
                .hasMessageContaining("Investigation relation anchor does not match source lineage");
    }

    @Test
    void anEdgeMayNotClaimASourceKindThatCannotProduceThatRelation() {
        // A card fingerprint does not live on a customer record. Allowing the pairing would make
        // the source column decorative rather than a lineage guarantee.
        assertThatThrownBy(() -> insertAnchor("SAME_CARD_FINGERPRINT",
                String.valueOf(anchor.get("relation_identity_hmac")), "correlation-v1",
                "CUSTOMER", payment.customerId()))
                .hasMessageContaining("ck_payment_investigation_relation_source");

        assertThatThrownBy(() -> insertAnchor("SAME_EMAIL",
                String.valueOf(anchor.get("relation_identity_hmac")), "correlation-v1",
                "RISK_CONTEXT", (UUID) anchor.get("source_id")))
                .hasMessageContaining("ck_payment_investigation_relation_source");
    }

    @Test
    void theEngineRefusesARelationTypeOutsideThePublishedVocabulary() {
        // Both the type check and the type-to-source pairing check enumerate the same closed
        // vocabulary, so an invented relation kind is refused whichever fires first. What matters
        // is that no unreviewed relation kind can ever reach the pivot.
        assertThatThrownBy(() -> insertAnchor("SAME_BROWSER_FONT_LIST",
                String.valueOf(anchor.get("relation_identity_hmac")), "correlation-v1",
                "PAYMENT_METHOD", (UUID) anchor.get("source_id")))
                .hasMessageMatching("(?s).*ck_payment_investigation_relation_(type|source).*");

        assertThatThrownBy(() -> insertAnchor("SAME_CARD_FINGERPRINT",
                String.valueOf(anchor.get("relation_identity_hmac")), "correlation-v1",
                "EXTERNAL_CONSORTIUM", (UUID) anchor.get("source_id")))
                .hasMessageMatching("(?s).*ck_payment_investigation_relation_source.*");
    }

    @Test
    void theEngineRefusesADigestOrKeyVersionThatCouldNotBeARealDerivation() {
        assertThatThrownBy(() -> insertAnchor("SAME_CARD_FINGERPRINT", "short",
                "correlation-v1", "PAYMENT_METHOD", (UUID) anchor.get("source_id")))
                .hasMessageContaining("ck_payment_investigation_relation_digest");

        // A padded digest would compare unequal to the same identity written without padding,
        // silently splitting one person into two.
        assertThatThrownBy(() -> insertAnchor("SAME_CARD_FINGERPRINT",
                " " + anchor.get("relation_identity_hmac") + " ", "correlation-v1",
                "PAYMENT_METHOD", (UUID) anchor.get("source_id")))
                .hasMessageContaining("ck_payment_investigation_relation_digest");

        assertThatThrownBy(() -> insertAnchor("SAME_CARD_FINGERPRINT",
                String.valueOf(anchor.get("relation_identity_hmac")), "",
                "PAYMENT_METHOD", (UUID) anchor.get("source_id")))
                .hasMessageContaining("ck_payment_investigation_relation_key");
    }

    @Test
    void theExactSameObservationCannotBeRecordedTwiceForOneAttempt() {
        assertThatThrownBy(() -> insertAnchor("SAME_CARD_FINGERPRINT",
                String.valueOf(anchor.get("relation_identity_hmac")),
                String.valueOf(anchor.get("identity_key_version")), "PAYMENT_METHOD",
                (UUID) anchor.get("source_id")))
                .hasMessageContaining("uk_payment_investigation_relation_alias");
    }

    @Test
    void theLookupAndPaymentIndexesTheBoundedPivotDependsOnAreInstalled() {
        List<String> indexes = jdbcTemplate.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE tablename = 'payment_investigation_relation_anchors'
                """, String.class);

        // Without the identity lookup index the pivot degrades to a merchant-wide scan, which is
        // both a latency incident and a denial-of-service surface on the console.
        assertThat(indexes).contains("idx_payment_investigation_relation_lookup",
                "idx_payment_investigation_relation_payment");
    }

    @Test
    void theProtectionTriggersFromTheMigrationAreActuallyAttachedToTheTable() {
        List<String> triggers = jdbcTemplate.queryForList("""
                SELECT tgname FROM pg_trigger
                WHERE tgrelid = 'payment_investigation_relation_anchors'::regclass
                  AND NOT tgisinternal
                """, String.class);

        assertThat(triggers).contains("trg_payment_investigation_relation_source",
                "trg_payment_investigation_relation_append_only");
    }

    @Test
    void everyAnchorSurvivingTheBackfillStillReconcilesWithItsSource() {
        Long inconsistent = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM payment_investigation_relation_anchors anchors
                WHERE (anchors.source_type = 'PAYMENT_METHOD' AND NOT EXISTS (
                        SELECT 1 FROM payment_methods m
                        WHERE m.id = anchors.source_id
                          AND m.merchant_id = anchors.merchant_id
                          AND m.card_fingerprint = anchors.relation_identity_hmac))
                   OR (anchors.source_type = 'CUSTOMER'
                       AND anchors.relation_type = 'SAME_EMAIL' AND NOT EXISTS (
                        SELECT 1 FROM customers c
                        WHERE c.id = anchors.source_id
                          AND c.merchant_id = anchors.merchant_id
                          AND c.email_hash = anchors.relation_identity_hmac))
                   OR (anchors.source_type = 'CUSTOMER'
                       AND anchors.relation_type = 'SAME_PHONE' AND NOT EXISTS (
                        SELECT 1 FROM customers c
                        WHERE c.id = anchors.source_id
                          AND c.merchant_id = anchors.merchant_id
                          AND c.phone_hash = anchors.relation_identity_hmac))
                """, Long.class);

        assertThat(inconsistent).isZero();
    }

    private void insertAnchor(String relationType, String digest, String keyVersion,
            String sourceType, UUID sourceId) {
        Instant now = clock.instant();
        jdbcTemplate.update("""
                INSERT INTO payment_investigation_relation_anchors
                    (id, merchant_id, payment_intent_id, payment_attempt_id, relation_type,
                     relation_identity_hmac, identity_key_version, observed_at, traffic_origin,
                     source_type, source_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), merchant.id(), payment.paymentIntentId(),
                anchor.get("payment_attempt_id"), relationType, digest, keyVersion,
                Timestamp.from(now), "MERCHANT_API", sourceType, sourceId, Timestamp.from(now));
    }
}
