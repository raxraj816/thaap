package com.thaap.payments.developer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Proves C57-CMP-001, the half a structural diff cannot reach.
 *
 * <p>The compatibility gate proves a field did not disappear. It says nothing about whether the
 * field still *means* what an integrated client assumed. A money amount that quietly becomes a
 * decimal, a basis-point score that becomes a percentage, or an instant that becomes a local date
 * are all additively compatible and all silently wrong: the client keeps parsing, and starts
 * being off by a factor of a hundred or a timezone. These are the meanings this chapter froze.
 */
class OpenApiSemanticLineageTests {

    private static final Path CONTRACT = Path.of("..", "..").normalize()
            .resolve("openapi/thaap-public-api-2026-08-01.yaml");

    private static JsonNode schemas() throws IOException {
        return new ObjectMapper().readTree(CONTRACT.toFile()).get("components").get("schemas");
    }

    /** Every property in the document, as (schema, property, node), so nothing is sampled. */
    private static List<Object[]> allProperties() throws IOException {
        List<Object[]> found = new ArrayList<>();
        JsonNode schemas = schemas();
        for (Map.Entry<String, JsonNode> schema : schemas.properties()) {
            JsonNode properties = schema.getValue().get("properties");
            if (properties == null) continue;
            for (Map.Entry<String, JsonNode> property : properties.properties()) {
                found.add(new Object[] {schema.getKey(), property.getKey(), property.getValue()});
            }
        }
        assertThat(found).as("the lineage scan is vacuous with no properties").isNotEmpty();
        return found;
    }

    @Test
    void everyMinorUnitMoneyFieldIsAnIntegerAndNeverADecimal() throws IOException {
        int checked = 0;
        for (Object[] entry : allProperties()) {
            String property = (String) entry[1];
            JsonNode node = (JsonNode) entry[2];
            if (!property.endsWith("Minor") && !property.endsWith("AmountMinor")) continue;
            checked++;
            assertThat(node.path("type").asText())
                    .as("%s.%s is a minor-unit amount and must stay an integer; a decimal here "
                            + "loses cents silently on every client", entry[0], property)
                    .isEqualTo("integer");
            assertThat(node.path("format").asText())
                    .as("%s.%s must be a 64-bit integer", entry[0], property)
                    .isEqualTo("int64");
        }
        assertThat(checked).as("no minor-unit money field was found to check").isGreaterThan(0);
    }

    @Test
    void everyBasisPointScoreIsAnIntegerSoItIsNeverReadAsAPercentage() throws IOException {
        int checked = 0;
        for (Object[] entry : allProperties()) {
            String property = (String) entry[1];
            JsonNode node = (JsonNode) entry[2];
            if (!property.toLowerCase().contains("basispoint")) continue;
            checked++;
            assertThat(node.path("type").asText())
                    .as("%s.%s is measured in basis points; making it fractional invites a "
                            + "client to render it as a percentage and be wrong by 100x",
                            entry[0], property)
                    .isEqualTo("integer");
        }
        assertThat(checked).as("no basis-point field was found to check").isGreaterThan(0);
    }

    /**
     * Temporal fields that are deliberately a calendar date rather than an instant.
     *
     * <p>The API version lifecycle is dated, not timestamped: `2026-08-01` is the version, and
     * giving it a time would imply a precision the contract does not have. The set is closed so a
     * genuine instant cannot quietly arrive as a bare date and shift every window a client
     * computes from it.
     */
    private static final List<String> CALENDAR_DATE_FIELDS = List.of(
            "VersionEntry.introducedAt", "VersionEntry.retirementDate");

    /**
     * Identifier fields that are deliberately not THAAP UUIDs, each for a stated reason.
     *
     * <p>A blanket "every id is a uuid" rule would force a lie into the contract. What must hold
     * is the distinction: an identifier THAAP issues is a UUID, while one supplied by a caller or
     * minted by an external system is a free string precisely because THAAP does not control its
     * shape. The set is closed so a new THAAP-issued identifier cannot arrive unconstrained.
     */
    private static final List<String> NON_UUID_IDENTIFIERS = List.of(
            // A correlation handle for support, not a resource.
            "ApiErrorResponse.requestId",
            // Minted by the processor; THAAP does not control the format.
            "PaymentAttemptAuthorizationResponse.processorReferenceId",
            "PaymentAttemptResponse.processorAuthorizationId",
            // Caller-supplied identity values, stored only as keyed digests.
            "RiskContextRequest.deviceId", "RiskContextRequest.sessionId",
            // Sandbox fixture slugs, chosen for readability in documentation.
            "StepView.fixtureId", "TestCardView.fixtureId");

    @Test
    void everyInstantFieldCarriesAnUnambiguousDateTimeFormat() throws IOException {
        int checked = 0;
        List<String> calendarDates = new ArrayList<>();
        for (Object[] entry : allProperties()) {
            String property = (String) entry[1];
            JsonNode node = (JsonNode) entry[2];
            boolean looksTemporal = property.endsWith("At") || property.endsWith("Time")
                    || property.equals("sourceAsOf") || property.equals("asOf")
                    || property.equals("sourceCutoff") || property.equals("timestamp");
            if (!looksTemporal || !node.path("type").asText().equals("string")) continue;
            String qualified = entry[0] + "." + property;
            if (node.path("format").asText().equals("date")) {
                calendarDates.add(qualified);
                continue;
            }
            checked++;
            assertThat(node.path("format").asText())
                    .as("%s is an instant and must declare date-time; a bare string invites a "
                            + "client to parse it as a local date and shift every window",
                            qualified)
                    .isEqualTo("date-time");
        }
        assertThat(checked).as("no instant field was found to check").isGreaterThan(0);
        assertThat(calendarDates)
                .as("a temporal field became a bare calendar date outside the closed set that "
                        + "is deliberately dated rather than timestamped")
                .allMatch(CALENDAR_DATE_FIELDS::contains);
    }

    @Test
    void everyThaapIssuedIdentifierIsADeclaredUuidAndEveryExceptionIsNamed() throws IOException {
        int checked = 0;
        List<String> unconstrained = new ArrayList<>();
        for (Object[] entry : allProperties()) {
            String property = (String) entry[1];
            JsonNode node = (JsonNode) entry[2];
            if (!property.endsWith("Id") || !node.path("type").asText().equals("string")) continue;
            if (!node.path("format").asText().equals("uuid")) {
                unconstrained.add(entry[0] + "." + property);
                continue;
            }
            checked++;
        }
        assertThat(checked).as("no THAAP-issued identifier was found to check").isGreaterThan(0);
        assertThat(unconstrained)
                .as("an identifier field is an unconstrained string without being named as a "
                        + "deliberate exception, so a generated client would accept a value the "
                        + "server rejects")
                .allMatch(NON_UUID_IDENTIFIERS::contains);
    }

    @Test
    void everyVersionAndCountFieldIsAnIntegerSoOrderingAndTotalsStayExact() throws IOException {
        int checked = 0;
        for (Object[] entry : allProperties()) {
            String property = (String) entry[1];
            JsonNode node = (JsonNode) entry[2];
            boolean looksOrdinal = property.endsWith("Version") || property.endsWith("Count")
                    || property.equals("streamVersion") || property.equals("ordinal");
            // Some version fields are deliberately strings (the date-based API version and the
            // schema version), so only the numeric ones are constrained here.
            if (!looksOrdinal || !node.path("type").asText().equals("number")) continue;
            checked++;
            assertThat(node.path("type").asText())
                    .as("%s.%s orders or totals things and must not be fractional",
                            entry[0], property)
                    .isEqualTo("integer");
        }
        assertThat(checked)
                .as("no numeric version or count field was fractional, which is the passing case")
                .isZero();
    }

    @Test
    void theProjectionFreshnessVocabularyStaysClosedAndNamed() throws IOException {
        JsonNode freshness = schemas().get("AnalyticsFreshness");
        assertThat(freshness).as("the freshness contract is what makes a derived number "
                + "interpretable, so it must be published").isNotNull();
        JsonNode state = freshness.get("properties").get("state");
        assertThat(state.has("enum"))
                .as("freshness state must be a closed enum; an open string lets the server "
                        + "invent a state no client knows how to treat")
                .isTrue();
        List<String> states = new ArrayList<>();
        state.get("enum").forEach(value -> states.add(value.asText()));
        assertThat(states).contains("FRESH", "STALE", "PARTIAL", "UNAVAILABLE");

        // The lineage fields a client needs to reproduce a number must all be present.
        JsonNode properties = freshness.get("properties");
        for (String required : List.of("generationId", "generationNumber", "sourceCutoff",
                "eligibleEventCount", "appliedEventCount", "trafficOriginPolicy")) {
            assertThat(properties.has(required))
                    .as("freshness omits %s, without which a published number cannot be "
                            + "reproduced later", required)
                    .isTrue();
        }
    }

    @Test
    void theStableErrorCodeVocabularyStaysOpenForAdditionButNamedForBranching()
            throws IOException {
        JsonNode error = schemas().get("ApiErrorResponse").get("properties").get("error");
        // x-extensible-enum rather than enum: adding a code must stay non-breaking, but the
        // known set must still be published so a client can branch on it.
        assertThat(error.has("x-extensible-enum"))
                .as("the error vocabulary must be published for branching").isTrue();
        assertThat(error.has("enum"))
                .as("a closed enum would make adding any new error code a breaking change")
                .isFalse();
        assertThat(error.get("x-extensible-enum").size()).isGreaterThan(50);
        assertThat(error.path("description").asText())
                .as("the contract must tell a client never to parse the message")
                .containsIgnoringCase("never parse");
    }
}
