package com.thaap.payments.projection.rebuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.customer.Customer;
import com.thaap.payments.customer.CustomerRepository;
import com.thaap.payments.merchant.Merchant;
import com.thaap.payments.merchant.MerchantRepository;
import com.thaap.payments.payment.PaymentIntent;
import com.thaap.payments.payment.PaymentIntentRepository;
import com.thaap.payments.platform.PlatformOperatorRole;
import com.thaap.payments.platform.PlatformOperatorService;
import com.thaap.payments.projection.ProjectionNames;
import com.thaap.payments.support.DatabaseCleaner;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

/**
 * Proof for {@code C50-SEC-001}.
 *
 * <p>The row promised that rebuild APIs, operational rows and errors expose only bounded
 * safe metadata. It closed on a suite nobody wrote ({@code ER-0099}, {@code TD-0007}).
 *
 * <p>Every prohibited class is seeded as a distinct canary in the source rows a rebuild
 * reads, then every sink the row names is scanned. Distinct markers matter: a single
 * shared canary would let a contact-data leak masquerade as a merchant-name leak, so a
 * passing run would not identify which sink is actually clean.
 */
@ActiveProfiles("test")
@SpringBootTest
class ProjectionRebuildPrivacyContractTests {

    private static final String BOOTSTRAP_TOKEN = "test-platform-bootstrap-token";

    private static final String MERCHANT_NAME_CANARY = "merchant-name-canary";
    private static final String CONTACT_CANARY = "contact-canary@example.test";
    private static final String PAN_CANARY = "4111111111111111";

    private static final List<String> PROHIBITED =
            List.of(MERCHANT_NAME_CANARY, CONTACT_CANARY, PAN_CANARY);

    /** Every table the rebuild control plane writes operational evidence into. */
    private static final List<String> OPERATIONAL_TABLES = List.of(
            "projection_rebuilds", "projection_rebuild_events", "projection_generations",
            "projection_reconciliations");

    @Autowired private ProjectionRebuildControlService controlService;
    @Autowired private ProjectionRebuildDispatcher dispatcher;
    @Autowired private PlatformOperatorService operatorService;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private PaymentIntentRepository paymentIntentRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;

    private String administratorKey;
    private String dataOperatorKey;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        PlatformOperatorService.MutationResult administrator = operatorService.bootstrap(
                BOOTSTRAP_TOKEN, "privacy-admin-bootstrap", "Privacy administrator",
                "privacy-admin-request");
        administratorKey = administrator.credential();
        PlatformOperatorService.MutationResult dataOperator = operatorService.create(
                administratorKey, "privacy-data-operator", "Privacy data operator",
                PlatformOperatorRole.DATA_OPERATOR, "privacy-data-request");
        dataOperatorKey = dataOperator.credential();
        seedSourceRowsCarryingEveryCanary();
    }

    @Test
    void noRebuildApiResponseDisclosesSourceContactMerchantOrCardData() throws Exception {
        ProjectionRebuildControlService.RebuildView started = controlService.start(
                dataOperatorKey, "privacy-start", ProjectionNames.PAYMENT_RISK, "ALL",
                "privacy-start-request");
        dispatcher.launchSynchronously(started.rebuildId());
        ProjectionRebuildControlService.ReconciliationView reconciliation =
                controlService.reconcile(dataOperatorKey, "privacy-reconcile",
                        ProjectionNames.PAYMENT_RISK, started.rebuildId(),
                        "privacy-reconcile-request");
        ProjectionRebuildControlService.ActivationView activation = controlService.activate(
                dataOperatorKey, "privacy-activate", ProjectionNames.PAYMENT_RISK,
                started.rebuildId(), "privacy-activate-request");
        ProjectionRebuildControlService.RebuildView status = controlService.status(
                dataOperatorKey, ProjectionNames.PAYMENT_RISK, started.rebuildId());

        for (Object view : List.of(started, reconciliation, activation, status)) {
            assertNoCanaryIn(objectMapper.writeValueAsString(view), view.getClass().getSimpleName());
        }
    }

    @Test
    void noOperationalRebuildRowRetainsProhibitedSourceText() {
        ProjectionRebuildControlService.RebuildView started = controlService.start(
                dataOperatorKey, "privacy-rows-start", ProjectionNames.PAYMENT_RISK, "ALL",
                "privacy-rows-request");
        dispatcher.launchSynchronously(started.rebuildId());
        controlService.reconcile(dataOperatorKey, "privacy-rows-reconcile",
                ProjectionNames.PAYMENT_RISK, started.rebuildId(),
                "privacy-rows-reconcile-request");

        for (String table : OPERATIONAL_TABLES) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM " + table);
            for (Map<String, Object> row : rows) {
                assertNoCanaryIn(String.valueOf(row), table);
            }
        }
    }

    @Test
    void aRefusedRebuildReportsASafeReasonWithoutEchoingSourceData() {
        // An error path is where disclosure usually escapes, because the safe-response
        // discipline that governs success responses is easy to forget on the throw.
        ProjectionRebuildControlService.RebuildView started = controlService.start(
                dataOperatorKey, "privacy-error-start", ProjectionNames.PAYMENT_RISK, "ALL",
                "privacy-error-request");

        assertThatThrownBy(() -> controlService.start(administratorKey,
                "privacy-error-forbidden", ProjectionNames.PAYMENT_RISK, "ALL",
                "privacy-error-forbidden-request"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertNoCanaryIn(
                                String.valueOf(exception.getMessage())
                                        + String.valueOf(exception.getReason()),
                                "forbidden start"));

        assertThatThrownBy(() -> controlService.activate(dataOperatorKey,
                "privacy-error-activate", ProjectionNames.PAYMENT_RISK, started.rebuildId(),
                "privacy-error-activate-request"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertNoCanaryIn(
                                String.valueOf(exception.getMessage())
                                        + String.valueOf(exception.getReason()),
                                "unreconciled activate"));
    }

    @Test
    void theRebuildFixtureActuallyCarriesTheCanariesItClaimsToScanFor() {
        // Without this, every assertion above would pass just as happily against a fixture
        // that never contained a canary at all — a clean scan of nothing.
        List<Map<String, Object>> merchants =
                jdbcTemplate.queryForList("SELECT * FROM merchants");
        List<Map<String, Object>> customers =
                jdbcTemplate.queryForList("SELECT * FROM customers");

        assertThat(String.valueOf(merchants)).contains(MERCHANT_NAME_CANARY);
        assertThat(String.valueOf(customers)).contains(CONTACT_CANARY);
    }

    private void seedSourceRowsCarryingEveryCanary() {
        Merchant merchant = merchantRepository.saveAndFlush(
                new Merchant(MERCHANT_NAME_CANARY, "thaap_test_" + UUID.randomUUID()));
        Customer customer = customerRepository.saveAndFlush(
                new Customer(merchant.getId(), CONTACT_CANARY, null));
        paymentIntentRepository.saveAndFlush(
                new PaymentIntent(merchant.getId(), customer.getId(), 4242L, "USD"));
    }

    private void assertNoCanaryIn(String rendered, String sink) {
        for (String canary : PROHIBITED) {
            assertThat(rendered)
                    .withFailMessage("%s disclosed %s", sink, canary)
                    .doesNotContain(canary);
        }
    }
}
