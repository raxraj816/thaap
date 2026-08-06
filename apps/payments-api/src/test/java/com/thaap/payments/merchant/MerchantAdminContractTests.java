package com.thaap.payments.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.security.SecretHasher;
import com.thaap.payments.security.SensitiveValueHasher;
import com.thaap.payments.support.DatabaseCleaner;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest
class MerchantAdminContractTests {

    private static final String API_KEY = "X-Api-Key";
    private static final String REQUEST_ID = "X-Request-Id";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ADMIN_BOOTSTRAP_TOKEN = "X-Admin-Bootstrap-Token";
    private static final String TEST_ADMIN_BOOTSTRAP_TOKEN = "test-admin-bootstrap-token";

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MerchantCreationRecordRepository merchantCreationRecordRepository;

    @Autowired
    private MerchantCreationIdentityRepository merchantCreationIdentityRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private SensitiveValueHasher sensitiveValueHasher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @BeforeEach
    void cleanDatabase() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(requestIdFilter)
                .build();
        databaseCleaner.clean();
    }

    @Test
    void merchantCreationRevealsRawApiKeyOnceAndPersistsOnlyPrefixAndHash() throws Exception {
        var response = mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_BOOTSTRAP_TOKEN, TEST_ADMIN_BOOTSTRAP_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Demo Merchant"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var json = objectMapper.readTree(response);
        var merchantId = UUID.fromString(json.get("id").asText());
        var apiKey = json.get("apiKey").asText();
        var apiKeyPrefix = json.get("apiKeyPrefix").asText();

        assertThat(json.get("name").asText()).isEqualTo("Demo Merchant");
        assertThat(json.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(json.has("apiKeyHash")).isFalse();
        assertThat(apiKey).startsWith("thaap_test_");
        assertThat(apiKey).startsWith(apiKeyPrefix);
        assertThat(apiKeyPrefix).isEqualTo(SecretHasher.prefix(apiKey));

        var stored = jdbcTemplate.queryForMap("""
                SELECT api_key_prefix, api_key_hash
                FROM merchants
                WHERE id = ?
                """, merchantId);
        assertThat(stored.get("API_KEY_PREFIX")).isEqualTo(apiKeyPrefix);
        assertThat(stored.get("API_KEY_HASH")).isEqualTo(SecretHasher.sha256(apiKey));
        assertThat(stored).doesNotContainValue(apiKey);

        var customerResponse = mockMvc.perform(post("/customers")
                        .header(API_KEY, apiKey)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "buyer@example.com"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var customer = objectMapper.readTree(customerResponse);

        assertThat(customer.get("merchantId").asText()).isEqualTo(merchantId.toString());
    }

    @Test
    void merchantCreationRequiresBootstrapToken() throws Exception {
        var response = mockMvc.perform(post("/admin/merchants")
                        .header(REQUEST_ID, "req_day5_merchant_bootstrap")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Demo Merchant"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(header().string(REQUEST_ID, "req_day5_merchant_bootstrap"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var error = objectMapper.readTree(response);

        assertThat(error.get("requestId").asText()).isEqualTo("req_day5_merchant_bootstrap");
        assertThat(error.get("status").asInt()).isEqualTo(403);
        assertThat(error.get("error").asText()).isEqualTo("forbidden");
        assertThat(error.get("message").asText()).isEqualTo("Invalid merchant admin bootstrap token");
        assertThat(error.get("path").asText()).isEqualTo("/admin/merchants");
    }

    @Test
    void merchantCreationIdempotencyReplaysTheSameMerchantAndApiKeyWithoutPlaintextStorage()
            throws Exception {
        String idempotencyKey = "merchant-create-replay";
        String requestBody = """
                {
                  "name": "Idempotent Merchant"
                }
                """;

        String firstResponse = mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_BOOTSTRAP_TOKEN, TEST_ADMIN_BOOTSTRAP_TOKEN)
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String replayResponse = mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_BOOTSTRAP_TOKEN, TEST_ADMIN_BOOTSTRAP_TOKEN)
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(replayResponse).isEqualTo(firstResponse);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM merchants", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM merchant_creation_records", Integer.class)).isEqualTo(1);
        String apiKey = objectMapper.readTree(firstResponse).get("apiKey").asText();
        String storedRecord = jdbcTemplate.queryForMap("""
                SELECT *
                FROM merchant_creation_records
                """).toString();
        assertThat(storedRecord)
                .doesNotContain(idempotencyKey)
                .doesNotContain(apiKey);
    }

    @Test
    void merchantCreationIdempotencyRejectsChangedRequest() throws Exception {
        mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_BOOTSTRAP_TOKEN, TEST_ADMIN_BOOTSTRAP_TOKEN)
                        .header(IDEMPOTENCY_KEY, "merchant-create-conflict")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Original Merchant"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_BOOTSTRAP_TOKEN, TEST_ADMIN_BOOTSTRAP_TOKEN)
                        .header(IDEMPOTENCY_KEY, "merchant-create-conflict")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Changed Merchant"}
                                """))
                .andExpect(status().isConflict());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM merchants", Integer.class)).isEqualTo(1);
    }

    @Test
    void merchantCreationReclaimsAReservationStrandedBeforeTheMerchantTransaction()
            throws Exception {
        String idempotencyKey = "merchant-create-stranded";
        String merchantName = "Recovered Merchant";
        Instant now = Instant.now();
        UUID abandonedLeaseToken = UUID.randomUUID();
        MerchantCreationRecord strandedRecord = new MerchantCreationRecord(
                sensitiveValueHasher.hmacSha256(
                        "merchant.creation.idempotency", idempotencyKey),
                sensitiveValueHasher.hmacSha256(
                        "merchant.creation.request", merchantName),
                sensitiveValueHasher.currentKeyVersion(),
                abandonedLeaseToken, now.minusSeconds(1),
                now.minusSeconds(60), now.plusSeconds(3600));
        merchantCreationRecordRepository.saveAndFlush(strandedRecord);
        saveCurrentIdentityAlias(strandedRecord, idempotencyKey, now);

        String response = mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_BOOTSTRAP_TOKEN, TEST_ADMIN_BOOTSTRAP_TOKEN)
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Recovered Merchant"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        MerchantCreationRecord completed = merchantCreationRecordRepository
                .findById(strandedRecord.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(MerchantCreationStatus.COMPLETED);
        assertThat(completed.getLeaseToken()).isNull();
        assertThat(completed.getLeaseExpiresAt()).isNull();
        assertThat(completed.getMerchantId())
                .isEqualTo(UUID.fromString(objectMapper.readTree(response).get("id").asText()));
        assertThat(merchantRepositoryCount()).isEqualTo(1);
    }

    @Test
    void merchantCreationDoesNotStealAnActiveReservation() throws Exception {
        String idempotencyKey = "merchant-create-active";
        String merchantName = "Active Merchant";
        Instant now = Instant.now();
        MerchantCreationRecord activeRecord = new MerchantCreationRecord(
                sensitiveValueHasher.hmacSha256(
                        "merchant.creation.idempotency", idempotencyKey),
                sensitiveValueHasher.hmacSha256(
                        "merchant.creation.request", merchantName),
                sensitiveValueHasher.currentKeyVersion(),
                UUID.randomUUID(), now.plusSeconds(60),
                now, now.plusSeconds(3600));
        merchantCreationRecordRepository.saveAndFlush(activeRecord);
        saveCurrentIdentityAlias(activeRecord, idempotencyKey, now);

        mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_BOOTSTRAP_TOKEN, TEST_ADMIN_BOOTSTRAP_TOKEN)
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Active Merchant"}
                                """))
                .andExpect(status().isConflict());

        assertThat(merchantRepositoryCount()).isZero();
        assertThat(merchantCreationRecordRepository.findById(activeRecord.getId()))
                .get()
                .extracting(MerchantCreationRecord::getLeaseToken)
                .isEqualTo(activeRecord.getLeaseToken());
    }

    @Test
    void merchantCreationRejectsMalformedIdempotencyKeys() throws Exception {
        mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_BOOTSTRAP_TOKEN, TEST_ADMIN_BOOTSTRAP_TOKEN)
                        .header(IDEMPOTENCY_KEY, " ")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Blank Key Merchant"}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_BOOTSTRAP_TOKEN, TEST_ADMIN_BOOTSTRAP_TOKEN)
                        .header(IDEMPOTENCY_KEY, "x".repeat(256))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "Oversized Key Merchant"}
                                """))
                .andExpect(status().isBadRequest());

        assertThat(merchantRepositoryCount()).isZero();
        assertThat(merchantCreationRecordRepository.count()).isZero();
    }

    @Test
    void merchantCreationReplaySurvivesPepperRotationAndPromotesTheNewIdentityAlias() {
        String oldPepper = "merchant-creation-old-pepper";
        String newPepper = "merchant-creation-new-pepper";
        SensitiveValueHasher oldHasher = new SensitiveValueHasher(
                oldPepper, "merchant-v1", "");
        MerchantCreationService oldService = merchantCreationService(oldHasher);
        MerchantCreationService.MerchantCreationResult original =
                oldService.create("rotation-safe-key", "Rotation Safe Merchant");

        String previousKeyring = "merchant-v1=" + Base64.getEncoder().encodeToString(
                oldPepper.getBytes(StandardCharsets.UTF_8));
        SensitiveValueHasher rotatedHasher = new SensitiveValueHasher(
                newPepper, "merchant-v2", previousKeyring);
        MerchantCreationService rotatedService = merchantCreationService(rotatedHasher);
        MerchantCreationService.MerchantCreationResult replayed =
                rotatedService.create("rotation-safe-key", "Rotation Safe Merchant");

        assertThat(replayed.merchant().getId()).isEqualTo(original.merchant().getId());
        assertThat(replayed.rawApiKey()).isEqualTo(original.rawApiKey());
        assertThat(merchantRepository.count()).isEqualTo(1);
        assertThat(merchantCreationRecordRepository.count()).isEqualTo(1);
        assertThat(merchantCreationIdentityRepository.count()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("""
                SELECT key_version
                FROM merchant_creation_identities
                ORDER BY key_version
                """, String.class)).containsExactly("merchant-v1", "merchant-v2");

        SensitiveValueHasher missingPreviousHasher = new SensitiveValueHasher(
                newPepper, "merchant-v2", "");
        MerchantCreationKeyConfiguration invalidConfiguration =
                new MerchantCreationKeyConfiguration(
                        merchantCreationRecordRepository, missingPreviousHasher, clock);
        assertThatThrownBy(invalidConfiguration::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("merchant-v1");
    }

    @Test
    void expiredMerchantCreationAllowsOldPepperRetirementAndIdempotencyKeyReuse() {
        String oldPepper = "merchant-creation-expired-old-pepper";
        String newPepper = "merchant-creation-expired-new-pepper";
        String idempotencyKey = "expired-rotation-safe-key";
        SensitiveValueHasher oldHasher = new SensitiveValueHasher(
                oldPepper, "merchant-v1", "");
        MerchantCreationService oldService = merchantCreationService(oldHasher);
        MerchantCreationService.MerchantCreationResult original =
                oldService.create(idempotencyKey, "Expired Rotation Merchant");
        UUID originalRecordId = merchantCreationRecordRepository.findAll()
                .getFirst()
                .getId();
        Instant now = clock.instant();
        jdbcTemplate.update("""
                UPDATE merchant_creation_records
                SET created_at = ?,
                    expires_at = ?
                WHERE id = ?
                """, now.minusSeconds(120), now.minusSeconds(1), originalRecordId);

        SensitiveValueHasher rotatedHasher = new SensitiveValueHasher(
                newPepper, "merchant-v2", "");
        MerchantCreationKeyConfiguration configuration =
                new MerchantCreationKeyConfiguration(
                        merchantCreationRecordRepository, rotatedHasher, clock);
        configuration.afterPropertiesSet();
        MerchantCreationService rotatedService = merchantCreationService(rotatedHasher);
        MerchantCreationService.MerchantCreationResult replacement =
                rotatedService.create(idempotencyKey, "Expired Rotation Merchant");

        assertThat(replacement.merchant().getId())
                .isNotEqualTo(original.merchant().getId());
        assertThat(replacement.rawApiKey()).isNotEqualTo(original.rawApiKey());
        assertThat(merchantRepository.count()).isEqualTo(2);
        assertThat(merchantCreationRecordRepository.count()).isEqualTo(2);
        assertThat(merchantCreationIdentityRepository.count()).isEqualTo(2);
    }

    @Test
    void expiredMerchantCreationStartsNewCredentialEpochWithoutPepperRotation() {
        String idempotencyKey = "expired-same-pepper-key";
        MerchantCreationService creationService = merchantCreationService(sensitiveValueHasher);
        MerchantCreationService.MerchantCreationResult original =
                creationService.create(idempotencyKey, "Same Pepper Merchant");
        UUID originalRecordId = merchantCreationRecordRepository.findAll()
                .getFirst()
                .getId();
        Instant now = clock.instant();
        jdbcTemplate.update("""
                UPDATE merchant_creation_records
                SET created_at = ?,
                    expires_at = ?
                WHERE id = ?
                """, now.minusSeconds(120), now.minusSeconds(1), originalRecordId);

        MerchantCreationService.MerchantCreationResult replacement =
                creationService.create(idempotencyKey, "Same Pepper Merchant");

        assertThat(replacement.merchant().getId())
                .isNotEqualTo(original.merchant().getId());
        assertThat(replacement.rawApiKey()).isNotEqualTo(original.rawApiKey());
        assertThat(merchantRepository.count()).isEqualTo(2);
        assertThat(merchantCreationRecordRepository.count()).isEqualTo(1);
        assertThat(merchantCreationIdentityRepository.count()).isEqualTo(1);
    }

    @Test
    void merchantCreationValidationUsesStandardErrorContract() throws Exception {
        var response = mockMvc.perform(post("/admin/merchants")
                        .header(ADMIN_BOOTSTRAP_TOKEN, TEST_ADMIN_BOOTSTRAP_TOKEN)
                        .header(REQUEST_ID, "req_day5_merchant_validation")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "name": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(REQUEST_ID, "req_day5_merchant_validation"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var error = objectMapper.readTree(response);

        assertThat(error.get("requestId").asText()).isEqualTo("req_day5_merchant_validation");
        assertThat(error.get("status").asInt()).isEqualTo(400);
        assertThat(error.get("error").asText()).isEqualTo("validation_failed");
        assertThat(error.get("message").asText()).isEqualTo("Request validation failed");
        assertThat(error.get("path").asText()).isEqualTo("/admin/merchants");
        assertThat(error.get("fieldErrors")).hasSize(1);
        assertThat(error.get("fieldErrors").get(0).get("field").asText()).isEqualTo("name");
    }

    private Integer merchantRepositoryCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM merchants", Integer.class);
    }

    private MerchantCreationService merchantCreationService(
            SensitiveValueHasher hasher) {
        return new MerchantCreationService(
                merchantRepository,
                merchantCreationRecordRepository,
                merchantCreationIdentityRepository,
                entityManager,
                hasher,
                clock,
                transactionManager,
                Duration.ofSeconds(30));
    }

    private void saveCurrentIdentityAlias(MerchantCreationRecord record,
            String idempotencyKey, Instant now) {
        merchantCreationIdentityRepository.saveAndFlush(
                new MerchantCreationIdentity(
                        sensitiveValueHasher.hmacSha256(
                                "merchant.creation.idempotency", idempotencyKey),
                        sensitiveValueHasher.currentKeyVersion(),
                        record.getId(),
                        now));
    }
}
