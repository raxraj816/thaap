package com.thaap.payments.eventpublication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.event.DomainEventActor;
import com.thaap.payments.event.DomainEventActorType;
import com.thaap.payments.event.DomainEventCatalogue;
import com.thaap.payments.event.DomainEventCommand;
import com.thaap.payments.event.DomainEventType;
import com.thaap.payments.event.DomainEventWriteResult;
import com.thaap.payments.event.DomainEventWriter;
import com.thaap.payments.event.DomainStreamType;
import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.platform.PlatformOperatorRole;
import com.thaap.payments.platform.PlatformOperatorService;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.TrafficOrigin;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

@ActiveProfiles("test")
@SpringBootTest
class PublicationRecoveryContractTests {

    @Autowired private PublicationRecoveryService recoveryService;
    @Autowired private PlatformOperatorService operatorService;
    @Autowired private DomainEventWriter eventWriter;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private RequestIdFilter requestIdFilter;

    private MockMvc mockMvc;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        transaction = new TransactionTemplate(transactionManager);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(requestIdFilter).build();
    }

    @Test
    void blockedRetryRequiresLiveDataOperatorAndIsIdempotentAndAudited() {
        PlatformOperatorService.MutationResult admin = operatorService.bootstrap(
                "test-platform-bootstrap-token", "recovery-bootstrap",
                "Recovery admin", "recovery-bootstrap-request");
        PlatformOperatorService.MutationResult dataOperator = operatorService.create(
                admin.credential(), "recovery-data-create", "Recovery data operator",
                PlatformOperatorRole.DATA_OPERATOR, "recovery-create-request");
        UUID publicationId = createBlockedPublication();

        assertThatThrownBy(() -> recoveryService.retry(admin.credential(),
                "recovery-key", publicationId, "admin-recovery-request"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.FORBIDDEN));

        PublicationRecoveryService.RecoveryResult created = recoveryService.retry(
                dataOperator.credential(), "recovery-key", publicationId,
                "data-recovery-request");
        PublicationRecoveryService.RecoveryResult replay = recoveryService.retry(
                dataOperator.credential(), "recovery-key", publicationId,
                "data-recovery-replay");

        assertThat(created.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT lifecycle_status FROM event_publications WHERE id = ?
                """, String.class, publicationId)).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM platform_data_operation_requests
                 WHERE operation = 'RETRY_PUBLICATION'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                 WHERE actor_type = 'PLATFORM_OPERATOR'
                   AND action = 'event_publication.retry_requested'
                """, Integer.class)).isEqualTo(1);
        assertThat(recoveryService.health(dataOperator.credential()).pendingCount())
                .isEqualTo(1);

        operatorService.disable(admin.credential(), "disable-data-operator",
                dataOperator.operator().getId(), "disable-request");
        assertThatThrownBy(() -> recoveryService.health(dataOperator.credential()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void recoverySurfaceIgnoresForwardedHeadersAndRequiresActualLoopbackPeer()
            throws Exception {
        mockMvc.perform(get("/internal/data/publications/health")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.25");
                            request.addHeader("X-Forwarded-For", "127.0.0.1");
                            return request;
                        })
                        .header("X-Platform-Operator-Key", "thaap_platform_invalid"))
                .andExpect(status().isForbidden());
    }

    private UUID createBlockedPublication() {
        Merchant merchant = merchantRepository.saveAndFlush(new Merchant(
                "recovery-merchant", "recovery-key-" + UUID.randomUUID()));
        DomainEventWriteResult result = transaction.execute(status ->
                eventWriter.append(command(merchant.getId(), UUID.randomUUID())));
        assertThat(result).isNotNull();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE event_publications
                   SET lifecycle_status = 'BLOCKED', blocked_at = ?, updated_at = ?,
                       last_outcome = 'PERMANENT_FAILURE',
                       last_error_code = 'AUTHORIZATION_REJECTED'
                 WHERE id = ?
                """, Timestamp.from(now), Timestamp.from(now), result.publicationId());
        return result.publicationId();
    }

    private DomainEventCommand command(UUID merchantId, UUID streamId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("amountMinor", 5000);
        payload.put("currency", "USD");
        payload.put("captureMethod", "AUTOMATIC");
        return new DomainEventCommand(merchantId, DomainStreamType.PAYMENT, streamId,
                DomainEventType.PAYMENT_INTENT_CREATED,
                DomainEventCatalogue.INITIAL_EVENT_VERSION,
                Instant.now().minusSeconds(1), "recovery-correlation", null,
                "recovery-request", null,
                new DomainEventActor(DomainEventActorType.SYSTEM,
                        "recovery-contract"), TrafficOrigin.MERCHANT_API, payload);
    }
}
