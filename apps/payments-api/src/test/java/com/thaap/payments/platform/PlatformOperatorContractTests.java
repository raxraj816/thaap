package com.thaap.payments.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thaap.payments.api.RequestIdFilter;
import com.thaap.payments.support.DatabaseCleaner;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest
class PlatformOperatorContractTests {

    private static final String BOOTSTRAP_HEADER = "X-Thaap-Platform-Bootstrap-Token";
    private static final String BOOTSTRAP_TOKEN = "test-platform-bootstrap-token";
    private static final String OPERATOR_HEADER = "X-Platform-Operator-Key";
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private RequestIdFilter requestIdFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleaner databaseCleaner;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(requestIdFilter)
                .build();
        databaseCleaner.clean();
    }

    @Test
    void bootstrapRevealsCredentialOnceAndExactReplayReturnsOnlySafeEvidence()
            throws Exception {
        MvcResult created = bootstrap("bootstrap-once", "Founding platform admin")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("PLATFORM_ADMIN"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.credentialRevealed").value(true))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();
        JsonNode createdJson = objectMapper.readTree(created.getResponse().getContentAsString());
        String credential = createdJson.get("credential").asText();
        UUID operatorId = UUID.fromString(createdJson.get("id").asText());

        assertThat(credential).startsWith("thaap_platform_");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM platform_operators", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM platform_operator_requests", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                WHERE merchant_id IS NULL
                  AND actor_type = 'PLATFORM_BOOTSTRAP'
                  AND action = 'platform_operator.bootstrapped'
                """, Integer.class)).isEqualTo(1);
        assertThat(databaseContains(credential)).isFalse();

        bootstrap("bootstrap-once", "Founding platform admin")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(operatorId.toString()))
                .andExpect(jsonPath("$.credential").doesNotExist())
                .andExpect(jsonPath("$.credentialRevealed").value(false))
                .andExpect(jsonPath("$.replayed").value(true));

        mockMvc.perform(get("/internal/platform/operators/me")
                        .header(OPERATOR_HEADER, credential))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(operatorId.toString()))
                .andExpect(jsonPath("$.operatorCode").value(createdJson.get("operatorCode").asText()))
                .andExpect(jsonPath("$.role").value("PLATFORM_ADMIN"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM platform_operator_requests", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs", Integer.class)).isEqualTo(1);
    }

    @Test
    void conflictingBootstrapReplayAndSecondBootstrapCannotCreateAnotherAdmin()
            throws Exception {
        bootstrap("bootstrap-conflict", "Admin one").andExpect(status().isCreated());

        bootstrap("bootstrap-conflict", "Changed name").andExpect(status().isConflict());
        bootstrap("another-bootstrap-key", "Admin two").andExpect(status().isConflict());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM platform_operators", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM platform_operator_requests", Integer.class)).isEqualTo(1);
    }

    @Test
    void administratorCreatesAndDisablesDataOperatorWithSafeIdempotentReplay()
            throws Exception {
        JsonNode admin = json(bootstrap("platform-admin", "Admin").andReturn());
        String adminKey = admin.get("credential").asText();

        MvcResult created = createOperator(adminKey, "create-data", "Data operator",
                        "DATA_OPERATOR")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.credentialRevealed").value(true))
                .andReturn();
        JsonNode dataOperator = json(created);
        UUID dataOperatorId = UUID.fromString(dataOperator.get("id").asText());
        String dataKey = dataOperator.get("credential").asText();

        createOperator(adminKey, "create-data", "Data operator", "DATA_OPERATOR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(dataOperatorId.toString()))
                .andExpect(jsonPath("$.credential").doesNotExist())
                .andExpect(jsonPath("$.credentialRevealed").value(false))
                .andExpect(jsonPath("$.replayed").value(true));

        createOperator(dataKey, "data-cannot-admin", "Forbidden admin", "PLATFORM_ADMIN")
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/internal/platform/operators/{operatorId}/disable", dataOperatorId)
                        .header(OPERATOR_HEADER, adminKey)
                        .header(IDEMPOTENCY_HEADER, "disable-data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.lifecycleEpoch").value(2))
                .andExpect(jsonPath("$.replayed").value(false));

        mockMvc.perform(post("/internal/platform/operators/{operatorId}/disable", dataOperatorId)
                        .header(OPERATOR_HEADER, adminKey)
                        .header(IDEMPOTENCY_HEADER, "disable-data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.replayed").value(true));

        mockMvc.perform(get("/internal/platform/operators/me")
                        .header(OPERATOR_HEADER, dataKey))
                .andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                WHERE actor_type = 'PLATFORM_OPERATOR'
                  AND action IN ('platform_operator.created', 'platform_operator.disabled')
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void localBoundaryRejectsRemoteBootstrapAndOperatorRequestsBeforeAuthentication()
            throws Exception {
        mockMvc.perform(post("/internal/platform/operators/bootstrap")
                        .with(remoteAddress("203.0.113.9"))
                        .header(BOOTSTRAP_HEADER, BOOTSTRAP_TOKEN)
                        .header(IDEMPOTENCY_HEADER, "remote-bootstrap")
                        .contentType(APPLICATION_JSON)
                        .content("{\"displayName\":\"Remote admin\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/internal/platform/operators/me")
                        .with(remoteAddress("2001:db8::10"))
                        .header(OPERATOR_HEADER, "thaap_platform_not-real"))
                .andExpect(status().isForbidden());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM platform_operators", Integer.class)).isZero();
    }

    @Test
    void bootstrapAndOperatorCredentialsAreNotInterchangeable() throws Exception {
        mockMvc.perform(get("/internal/platform/operators/me")
                        .header(OPERATOR_HEADER, BOOTSTRAP_TOKEN))
                .andExpect(status().isUnauthorized());

        JsonNode admin = json(bootstrap("credential-separation", "Admin").andReturn());
        mockMvc.perform(post("/internal/platform/operators/bootstrap")
                        .header(BOOTSTRAP_HEADER, admin.get("credential").asText())
                        .header(IDEMPOTENCY_HEADER, "operator-as-bootstrap")
                        .contentType(APPLICATION_JSON)
                        .content("{\"displayName\":\"Wrong identity\"}"))
                .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions bootstrap(
            String idempotencyKey, String displayName) throws Exception {
        return mockMvc.perform(post("/internal/platform/operators/bootstrap")
                .header(BOOTSTRAP_HEADER, BOOTSTRAP_TOKEN)
                .header(IDEMPOTENCY_HEADER, idempotencyKey)
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(
                        new BootstrapRequest(displayName))));
    }

    private org.springframework.test.web.servlet.ResultActions createOperator(
            String adminKey, String idempotencyKey, String displayName, String role)
            throws Exception {
        return mockMvc.perform(post("/internal/platform/operators")
                .header(OPERATOR_HEADER, adminKey)
                .header(IDEMPOTENCY_HEADER, idempotencyKey)
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(
                        new CreateRequest(displayName, role))));
    }

    private boolean databaseContains(String secret) {
        Integer matches = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM platform_operator_credentials
                WHERE secret_prefix = ?
                   OR secret_sha256 = ?
                   OR secret_identity_hmac = ?
                """, Integer.class, secret, secret, secret);
        return matches != null && matches > 0;
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private RequestPostProcessor remoteAddress(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private record BootstrapRequest(String displayName) {
    }

    private record CreateRequest(String displayName, String role) {
    }
}
