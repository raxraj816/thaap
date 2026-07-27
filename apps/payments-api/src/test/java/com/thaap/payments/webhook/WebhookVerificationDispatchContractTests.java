package com.thaap.payments.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.support.DatabaseCleaner;
import com.thaap.payments.tenant.MerchantContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("test")
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:thaap_webhook_verification_dispatch;"
                + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH")
class WebhookVerificationDispatchContractTests {

    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private WebhookEndpointService endpointService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private WebhookEndpointVerificationService verificationService;

    @MockitoSpyBean
    private WebhookEndpointSecretService endpointSecretService;

    @MockitoBean
    private WebhookHttpTransport transport;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
    }

    @Test
    void terminalChallengeWinnerAfterSigningRevokesDispatchBeforeAnyNetworkSideEffect() {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant("verification-terminal-fence", "thaap_verification_terminal_fence"));
        MerchantContext context = new MerchantContext(
                merchant.getId(), "verification-terminal-fence", merchant.getApiKeyPrefix());
        WebhookEndpointService.EndpointSecretResult endpoint = endpointService.create(
                context, "verification-terminal-endpoint",
                "http://127.0.0.1:9191/verification-terminal-fence",
                Set.of("refund.created"), "verification-terminal-endpoint");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        doAnswer(invocation -> {
            WebhookEndpointVerificationService.PreparedChallenge prepared =
                    invocation.getArgument(1);
            Instant terminalAt = clock.instant();
            transaction.executeWithoutResult(status -> {
                jdbcTemplate.update("""
                        UPDATE webhook_endpoints
                           SET verification_status = 'EXPIRED', pending_verification_id = NULL
                         WHERE merchant_id = ? AND id = ?
                        """, merchant.getId(), prepared.endpointId());
                jdbcTemplate.update("""
                        UPDATE webhook_endpoint_verifications
                           SET lifecycle_status = 'EXPIRED', failure_code = 'EXPIRED', completed_at = ?
                         WHERE merchant_id = ? AND id = ?
                        """, Timestamp.from(terminalAt), merchant.getId(), prepared.id());
                jdbcTemplate.update("""
                        INSERT INTO webhook_endpoint_verification_events
                            (id, merchant_id, webhook_endpoint_verification_id,
                             event_sequence, event_type, outcome_code, occurred_at)
                        VALUES (?, ?, ?, 2, 'EXPIRED', 'EXPIRED', ?)
                        """, UUID.randomUUID(), merchant.getId(), prepared.id(),
                        Timestamp.from(terminalAt));
            });
            return invocation.callRealMethod();
        }).when(verificationService).acquireDispatchPermit(
                eq(merchant.getId()), any(WebhookEndpointVerificationService.PreparedChallenge.class));

        WebhookEndpointVerificationService.ChallengeView result = verificationService.verify(
                context, endpoint.endpoint().getId(),
                "verification-terminal-key", "verification-terminal-request");

        assertThat(result.lifecycleStatus()).isEqualTo(WebhookEndpointVerificationStatus.EXPIRED);
        assertThat(result.failureCode()).isEqualTo("EXPIRED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT dispatch_started_at IS NULL
                  FROM webhook_endpoint_verifications
                 WHERE id = ?
                """, Boolean.class, result.id())).isTrue();
        verify(endpointSecretService).signForDelivery(eq(merchant.getId()),
                eq(endpoint.endpoint().getId()), any(UUID.class), any(String.class),
                any(Instant.class), any(WebhookSigner.class));
        verifyNoInteractions(transport);
    }
}
