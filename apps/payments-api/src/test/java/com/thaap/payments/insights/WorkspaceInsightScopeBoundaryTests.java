package com.thaap.payments.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * Proves C56-SCP-001. The insight capability reads and aggregates; it never writes, never becomes
 * a second aggregate store, and never claims the causal language the product has explicitly
 * refused. "Prevented fraud" and "ROI" are the numbers every fraud dashboard wants to show and
 * none of them can defend, so their absence is asserted rather than trusted.
 */
class WorkspaceInsightScopeBoundaryTests {

    private static final Path INSIGHTS_PACKAGE = Path.of("src", "main", "java", "com", "thaap",
            "payments", "insights");
    private static final Path REPOSITORY_ROOT = Path.of("..", "..").normalize();

    /** Services that would let an insight read mutate fraud, money, or control truth. */
    private static final List<String> PROHIBITED_IMPORTS = List.of(
            "com.thaap.payments.review.ReviewOperationsService",
            "com.thaap.payments.bulk.ReviewBulkActionService",
            "com.thaap.payments.dispute.ChargebackService",
            "com.thaap.payments.payment.FullRefundService",
            "com.thaap.payments.controls.GovernedControlService",
            "com.thaap.payments.fraudlist.FraudListManagementService");

    /** Claims the product has refused to make, in any casing a template might produce. */
    private static final List<String> PROHIBITED_CLAIMS = List.of(
            "preventedfraud", "preventedloss", "fraudprevented", "lossavoided",
            "interchangesaving", "profit", "roi", "causallift", "modellift", "uplift");

    @Test
    void theInsightPackageNeverImportsAMutatingDomainService() throws IOException {
        for (Path javaFile : sourceFiles()) {
            String source = Files.readString(javaFile, StandardCharsets.UTF_8);
            for (String prohibited : PROHIBITED_IMPORTS) {
                assertThat(source).as("%s reaches a mutating service", javaFile)
                        .doesNotContain(prohibited);
            }
        }
    }

    @Test
    void theInsightControllerDeclaresOnlyReadMappings() {
        for (Method method : WorkspaceInsightsController.class.getDeclaredMethods()) {
            for (Class<? extends java.lang.annotation.Annotation> mutation :
                    List.of(PostMapping.class, PutMapping.class, PatchMapping.class,
                            DeleteMapping.class)) {
                assertThat(method.isAnnotationPresent(mutation))
                        .as("%s declares %s on an aggregate read surface",
                                method.getName(), mutation.getSimpleName())
                        .isFalse();
            }
        }
    }

    @Test
    void theInsightPackageIssuesNoWritingSqlAtAll() throws IOException {
        for (Path javaFile : sourceFiles()) {
            String source = Files.readString(javaFile, StandardCharsets.UTF_8).toUpperCase();
            // Markers are the SQL statement forms, so Java's own `truncatedTo(...)` does not
            // read as a TRUNCATE.
            for (String write : List.of("INSERT INTO", "UPDATE ", "DELETE FROM", "MERGE INTO",
                    "CREATE TABLE", "TRUNCATE TABLE")) {
                assertThat(source).as("%s issues writing SQL from a read surface", javaFile)
                        .doesNotContain(write);
            }
        }
    }

    @Test
    void noInsightSurfaceClaimsPreventedFraudProfitOrCausalLift() throws IOException {
        for (Path javaFile : sourceFiles()) {
            String source = Files.readString(javaFile, StandardCharsets.UTF_8)
                    .toLowerCase().replace("_", "").replace(" ", "");
            for (String claim : PROHIBITED_CLAIMS) {
                assertThat(source).as("%s makes the unsupportable claim %s", javaFile, claim)
                        .doesNotContain(claim);
            }
        }
    }

    @Test
    void theInsightSurfaceIntroducesNoSecondAggregateStore() throws IOException {
        // The plan defers a warehouse or column store to a later phase with its own
        // dual-store reconciliation design. Reading the existing projection is in scope;
        // standing up another engine is not.
        for (Path javaFile : sourceFiles()) {
            String source = Files.readString(javaFile, StandardCharsets.UTF_8).toLowerCase();
            for (String store : List.of("clickhouse", "bigquery", "snowflake", "redshift",
                    "elasticsearch", "opensearch", "druid", "materialized view")) {
                assertThat(source).as("%s introduces the aggregate store %s", javaFile, store)
                        .doesNotContain(store);
            }
        }
    }

    @Test
    void theOnlyMigrationThisCapabilityAddsIsQuerySupportIndexes() throws IOException {
        Path migration = REPOSITORY_ROOT.resolve(
                "apps/payments-api/src/main/resources/db/migration/"
                        + "V91__support_workspace_insight_queries.sql");
        assertThat(Files.exists(migration)).isTrue();
        String source = Files.readString(migration, StandardCharsets.UTF_8).toUpperCase();

        // Index creation only: a new table here would be the second aggregate store by stealth.
        assertThat(source).contains("CREATE INDEX");
        for (String structural : List.of("CREATE TABLE", "ALTER TABLE", "DROP ", "INSERT INTO",
                "UPDATE ", "DELETE FROM", "CREATE MATERIALIZED")) {
            assertThat(source).as("V91 does more than add query-support indexes: %s", structural)
                    .doesNotContain(structural);
        }
    }

    @Test
    void everyIndexV91AddsIsTenantLeadingSoItCannotServeACrossTenantScan() throws IOException {
        Path migration = REPOSITORY_ROOT.resolve(
                "apps/payments-api/src/main/resources/db/migration/"
                        + "V91__support_workspace_insight_queries.sql");
        String source = Files.readString(migration, StandardCharsets.UTF_8);

        List<String> indexBodies = Stream.of(source.split("CREATE INDEX")).skip(1).toList();
        assertThat(indexBodies).as("the scan is vacuous without an index to check").isNotEmpty();
        for (String body : indexBodies) {
            String columns = body.substring(body.indexOf('(') + 1);
            String first = columns.split(",")[0].trim();
            assertThat(first)
                    .as("an index whose leading column is not the tenant invites a plan that "
                            + "scans across merchants: %s", first)
                    .isEqualTo("merchant_id");
        }
    }

    private List<Path> sourceFiles() throws IOException {
        try (Stream<Path> paths = Files.list(INSIGHTS_PACKAGE)) {
            List<Path> files = paths.filter(path -> path.toString().endsWith(".java")).toList();
            assertThat(files).as("the scan is vacuous with no insight sources").isNotEmpty();
            return files;
        }
    }
}
