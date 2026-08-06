package com.thaap.payments.developer;

import com.thaap.payments.api.ApiVersionFilter;
import com.thaap.payments.api.ApiErrorResponse;
import com.thaap.payments.api.ApiErrorField;
import com.thaap.payments.api.PublicApiErrorDescriptor;
import com.thaap.payments.risk.RiskOperatorContext;
import com.thaap.payments.tenant.MerchantContext;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.utils.SpringDocUtils;
import java.util.LinkedHashSet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Configuration
public class PublicOpenApiConfiguration {

    private static final Set<String> CREATED_OPERATIONS = Set.of(
            "POST /customers",
            "POST /developer/scenario-runs",
            "POST /payment_intents",
            "POST /payment_intents/{id}/confirm",
            "POST /payment_methods",
            "POST /refunds",
            "POST /risk/controls/exceptions",
            "POST /risk/controls/rules",
            "POST /risk/fraud-lists",
            "POST /risk/fraud-lists/{listId}/entries",
            "POST /risk/fraud-lists/{listId}/versions",
            "POST /risk/monitoring/snapshots",
            "POST /risk/operators",
            "POST /risk/policies",
            "POST /risk/policies/{policyId}/thresholds",
            "POST /risk/profiles",
            "POST /risk/replay-datasets",
            "POST /risk/reviews/{id}/annotations",
            "POST /risk/simulations",
            "POST /risk/workspace/payment-views",
            "POST /risk/workspace/reviews/{reviewCaseId}/notes",
            "POST /risk/workspace/saved-searches",
            "POST /webhook_endpoints");
    private static final Set<String> ACCEPTED_OPERATIONS = Set.of(
            "POST /risk/workspace/review-bulk-actions");
    private static final String EXTENSIBLE_ENUM = "x-extensible-enum";
    private static final String SCENARIO_RUNNER_TOKEN_HEADER = "X-Thaap-Scenario-Runner-Token";

    static {
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(
                MerchantContext.class, RiskOperatorContext.class);
    }

    @Bean
    GroupedOpenApi publicApiContract(
            @Qualifier("requestMappingHandlerMapping")
            ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider) {
        return GroupedOpenApi.builder().group("public")
                .pathsToMatch("/**")
                .pathsToExclude("/internal/**", "/actuator/**", "/admin/**", "/platform/**")
                .addOpenApiCustomizer(publicContractCustomizer(handlerMappingProvider)).build();
    }

    @Bean
    OpenApiCustomizer publicContractCustomizer(
            @Qualifier("requestMappingHandlerMapping")
            ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider) {
        return openApi -> {
            // Resolved lazily, at document-generation time rather than at bean creation,
            // so reading the handler mapping cannot introduce a startup cycle.
            Set<String> trustedScenarioOperations =
                    trustedScenarioOperations(handlerMappingProvider);
            openApi.info(new Info().title("THAAP Public Payment Risk API")
                    .version(ApiVersionFilter.CURRENT_VERSION)
                    .description("Versioned merchant, risk-operator, and sandbox contract."));
            openApi.servers(java.util.List.of(new Server().url("http://127.0.0.1:8080")
                    .description("Local sandbox; SDK clients reject redirects and non-HTTP(S) schemes.")));
            Components components = openApi.getComponents() == null
                    ? new Components() : openApi.getComponents();
            ModelConverters.getInstance().read(ApiErrorResponse.class)
                    .forEach(components::addSchemas);
            ModelConverters.getInstance().read(ApiErrorField.class)
                    .forEach(components::addSchemas);
            publishStableErrorCatalogue(components);
            components.addSecuritySchemes("merchantApi", apiKey("X-Api-Key"));
            components.addSecuritySchemes("riskOperator", apiKey("X-Risk-Operator-Key"));
            components.addSecuritySchemes("scenarioRunner", apiKey(SCENARIO_RUNNER_TOKEN_HEADER));
            components.getSchemas().values().forEach(schema -> normalizeOpaqueMaps(
                    schema, java.util.Collections.newSetFromMap(new IdentityHashMap<>())));
            components.getSchemas().values().stream()
                    .filter(schema -> schema.getProperties() != null)
                    .map(schema -> schema.getProperties().get("currency"))
                    .filter(java.util.Objects::nonNull)
                    .forEach(currency -> ((Schema<?>) currency).setExample("USD"));
            openApi.components(components);
            if (openApi.getPaths() == null) return;
            openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                String methodName = method.name().toUpperCase();
                operation.setOperationId(stableOperationId(method.name(), path));
                operation.setSummary(methodName + " " + path);
                operation.addParametersItem(new HeaderParameter().name(ApiVersionFilter.HEADER)
                        .required(false).schema(new StringSchema()
                                ._default(ApiVersionFilter.CURRENT_VERSION)
                                .example(ApiVersionFilter.CURRENT_VERSION))
                        .description("Date-based API contract version; absence resolves compatibly."));
                operation.getParameters().stream()
                        .filter(parameter -> "header".equals(parameter.getIn())
                                && "Idempotency-Key".equals(parameter.getName()))
                        .forEach(parameter -> parameter.setDescription(
                                "Stable mutation identity. An ambiguous retry must reuse the exact key and request body."));
                MediaType errorMedia = new MediaType().schema(
                        new Schema<>().$ref("#/components/schemas/ApiErrorResponse"))
                        .example(Map.of(
                                "requestId", "018f4f47-f63b-7d22-a2e4-3b66f5748088",
                                "timestamp", "2026-08-01T00:00:00Z",
                                "status", PublicApiErrorDescriptor.PAYMENT_INTENT_NOT_CAPTURABLE.status().value(),
                                "error", PublicApiErrorDescriptor.PAYMENT_INTENT_NOT_CAPTURABLE.code(),
                                "message", PublicApiErrorDescriptor.PAYMENT_INTENT_NOT_CAPTURABLE.safeMessage(),
                                "path", path,
                                "retryable", PublicApiErrorDescriptor.PAYMENT_INTENT_NOT_CAPTURABLE.retryable(),
                                "recoveryAction",
                                PublicApiErrorDescriptor.PAYMENT_INTENT_NOT_CAPTURABLE.recoveryAction()));
                operation.getResponses().addApiResponse("default", new ApiResponse()
                        .description("Stable ApiErrorResponse; classify using the error field.")
                        .content(new Content().addMediaType("application/json", errorMedia)));
                normalizeSuccessStatus(methodName, path, operation);
                operation.getResponses().forEach((status, response) -> response.addHeaderObject(
                        ApiVersionFilter.HEADER, new io.swagger.v3.oas.models.headers.Header()
                                .schema(new StringSchema().example(ApiVersionFilter.CURRENT_VERSION))
                                .description("Effective API version")));
                List<SecurityRequirement> requirements =
                        security(path, methodName, trustedScenarioOperations);
                operation.setSecurity(requirements);
                // Only where the token is optional. On the trusted scenario operations it
                // is already a security requirement, and publishing it twice would let a
                // client send it as a bare header and believe it had satisfied the scheme.
                if (requirements.stream().anyMatch(requirement -> requirement.containsKey("merchantApi"))
                        && requirements.stream().noneMatch(
                                requirement -> requirement.containsKey("scenarioRunner"))) {
                    operation.addParametersItem(new HeaderParameter()
                            .name(SCENARIO_RUNNER_TOKEN_HEADER).required(false)
                            .schema(new StringSchema())
                            .description("Optional sandbox credential. When present it must be valid and "
                                    + "marks the call as synthetic scenario traffic; it never replaces "
                                    + "the merchant credential."));
                }
            }));
        };
    }

    /**
     * Publishes the closed error catalogue into the contract so {@code error} is a
     * documented machine value rather than an opaque string. The enumeration is read
     * from {@link PublicApiErrorDescriptor} at generation time, so the published
     * contract cannot drift from the codes the API actually emits.
     *
     * <p>The catalogue is published as {@code x-extensible-enum} rather than a closed
     * {@code enum}. A closed enum on a response property would make every future code
     * addition a breaking change for strictly validating generated clients, which
     * contradicts the promise that codes are only ever added.
     */
    private void publishStableErrorCatalogue(Components components) {
        Schema<?> errorResponse = components.getSchemas().get("ApiErrorResponse");
        if (errorResponse == null || errorResponse.getProperties() == null) {
            throw new IllegalStateException("ApiErrorResponse schema is missing from the public contract");
        }
        List<String> codes = Arrays.stream(PublicApiErrorDescriptor.values())
                .map(PublicApiErrorDescriptor::code)
                .distinct()
                .sorted()
                .toList();
        List<String> recoveryActions = Arrays.stream(PublicApiErrorDescriptor.values())
                .map(PublicApiErrorDescriptor::recoveryAction)
                .distinct()
                .sorted()
                .toList();

        Schema<Object> errorProperty = property(errorResponse, "error");
        errorProperty.setDescription("Stable machine-readable failure class. Branch on this value; "
                + "never parse message. Codes are only ever added, never repurposed, so clients must "
                + "treat an unrecognised code as a generic failure of the documented HTTP status.");
        errorProperty.addExtension(EXTENSIBLE_ENUM, codes);

        Schema<Object> recoveryProperty = property(errorResponse, "recoveryAction");
        recoveryProperty.setDescription("Stable machine-readable remediation hint paired with error.");
        recoveryProperty.addExtension(EXTENSIBLE_ENUM, recoveryActions);

        property(errorResponse, "message").setDescription("Reviewed human guidance. "
                + "Not a compatibility contract; wording may change without notice and must not be parsed.");

        property(errorResponse, "retryable").setDescription("True only when replaying the identical "
                + "request with the same Idempotency-Key can succeed without operator intervention.");
    }

    @SuppressWarnings("unchecked")
    private Schema<Object> property(Schema<?> schema, String name) {
        Schema<Object> property = (Schema<Object>) schema.getProperties().get(name);
        if (property == null) {
            throw new IllegalStateException("ApiErrorResponse is missing the " + name + " property");
        }
        return property;
    }

    private void normalizeOpaqueMaps(Schema<?> schema, Set<Schema<?>> visited) {
        if (schema == null || !visited.add(schema)) return;
        Object additionalProperties = schema.getAdditionalProperties();
        if (additionalProperties instanceof Schema<?> valueSchema) {
            if ("object".equals(valueSchema.getType())
                    && valueSchema.get$ref() == null
                    && (valueSchema.getProperties() == null || valueSchema.getProperties().isEmpty())
                    && valueSchema.getItems() == null
                    && valueSchema.getAllOf() == null
                    && valueSchema.getOneOf() == null
                    && valueSchema.getAnyOf() == null) {
                schema.setAdditionalProperties(Boolean.TRUE);
            } else {
                normalizeOpaqueMaps(valueSchema, visited);
            }
        }
        if (schema.getProperties() != null) {
            schema.getProperties().values().forEach(property -> {
                Schema<?> propertySchema = (Schema<?>) property;
                if (isUnconstrainedObject(propertySchema)) propertySchema.setType(null);
                normalizeOpaqueMaps(propertySchema, visited);
            });
        }
        Schema<?> items = schema.getItems();
        if (isUnconstrainedObject(items)) items.setType(null);
        normalizeOpaqueMaps(items, visited);
        if (schema.getAllOf() != null) {
            schema.getAllOf().forEach(item -> normalizeOpaqueMaps(item, visited));
        }
        if (schema.getOneOf() != null) {
            schema.getOneOf().forEach(item -> normalizeOpaqueMaps(item, visited));
        }
        if (schema.getAnyOf() != null) {
            schema.getAnyOf().forEach(item -> normalizeOpaqueMaps(item, visited));
        }
    }

    private boolean isUnconstrainedObject(Schema<?> schema) {
        return schema != null && "object".equals(schema.getType())
                && schema.get$ref() == null
                && (schema.getProperties() == null || schema.getProperties().isEmpty())
                && schema.getAdditionalProperties() == null
                && schema.getItems() == null
                && schema.getAllOf() == null
                && schema.getOneOf() == null
                && schema.getAnyOf() == null;
    }

    private void normalizeSuccessStatus(String method, String path,
            io.swagger.v3.oas.models.Operation operation) {
        String operationKey = method + " " + path;
        String expected = CREATED_OPERATIONS.contains(operationKey) ? "201"
                : ACCEPTED_OPERATIONS.contains(operationKey) ? "202" : null;
        if (expected == null || operation.getResponses().containsKey(expected)) return;
        String existing = operation.getResponses().keySet().stream()
                .filter(status -> status.matches("2[0-9][0-9]"))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "Public mutation has no success response: " + operationKey));
        ApiResponse response = operation.getResponses().remove(existing);
        response.setDescription("201".equals(expected) ? "Created" : "Accepted");
        operation.getResponses().addApiResponse(expected, response);
    }

    private String stableOperationId(String method, String path) {
        StringBuilder result = new StringBuilder(method.toLowerCase());
        boolean uppercase = true;
        for (int index = 0; index < path.length(); index++) {
            char character = path.charAt(index);
            if (character == '{') {
                result.append("By");
                uppercase = true;
                continue;
            }
            if (Character.isLetterOrDigit(character)) {
                result.append(uppercase ? Character.toUpperCase(character) : character);
                uppercase = false;
            } else {
                uppercase = true;
            }
        }
        return result.toString();
    }

    private SecurityScheme apiKey(String header) {
        return new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER).name(header);
    }

    /**
     * The operations whose handler is annotated {@link RequiresScenarioRunner}. Derived from
     * the running handler mapping rather than from a list in this file, so the published
     * credential requirement cannot drift from the code that enforces it.
     */
    private Set<String> trustedScenarioOperations(
            ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider) {
        RequestMappingHandlerMapping handlerMapping = handlerMappingProvider.getIfAvailable();
        if (handlerMapping == null) {
            throw new IllegalStateException(
                    "the public contract cannot be generated without the handler mapping");
        }
        Set<String> operations = new LinkedHashSet<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            if (!hasScenarioRunnerRequirement(handler)) {
                return;
            }
            Set<String> paths = info.getPathPatternsCondition() == null
                    ? (info.getPatternsCondition() == null
                            ? Set.of() : info.getPatternsCondition().getPatterns())
                    : info.getPathPatternsCondition().getPatternValues();
            paths.forEach(path -> info.getMethodsCondition().getMethods()
                    .forEach(requestMethod -> operations.add(requestMethod.name() + " " + path)));
        });
        return operations;
    }

    private boolean hasScenarioRunnerRequirement(Object handler) {
        return handler instanceof HandlerMethod handlerMethod
                && handlerMethod.getMethodAnnotation(RequiresScenarioRunner.class) != null;
    }

    private List<SecurityRequirement> security(String path, String method,
            Set<String> trustedScenarioOperations) {
        if ((path.equals("/risk/operators") && "POST".equals(method))
                || (path.matches("/risk/operators/[^/]+/disable") && "POST".equals(method))) {
            return List.of(new SecurityRequirement().addList("merchantApi"));
        }
        if (path.startsWith("/risk/")) {
            return List.of(new SecurityRequirement().addList("riskOperator"));
        }
        if (path.startsWith("/developer/api-versions")
                || path.startsWith("/developer/error-catalog")
                || path.startsWith("/developer/openapi")
                || path.equals("/developer/test-cards")) {
            return List.of();
        }
        // The runner token is not a separate credential class. On an ordinary merchant
        // operation it is optional: MerchantContextArgumentResolver accepts it and flips
        // TrafficOrigin to SYNTHETIC_SCENARIO, so it is published as an optional header
        // rather than a security requirement. Declaring it required there would tell a
        // generated client that a merchant key alone is insufficient, which the resolver
        // does not enforce.
        //
        // The three trusted scenario operations are the exception. Each calls
        // DeveloperScenarioController.requireScenarioRunner and rejects a merchant key
        // presented alone, so for those a merchant key genuinely is insufficient and the
        // contract must say so — otherwise a generated client omits the token and the
        // integrator meets an avoidable SCENARIO_RUNNER_CREDENTIAL_REQUIRED.
        if (trustedScenarioOperations.contains(method + " " + path)) {
            return List.of(new SecurityRequirement()
                    .addList("merchantApi").addList("scenarioRunner"));
        }
        return List.of(new SecurityRequirement().addList("merchantApi"));
    }
}
