package com.thaap.payments.bulk;

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
import org.springframework.web.bind.annotation.PutMapping;

/**
 * Proves C55-SCP-001: this capability owns at-most-25 assignment/release orchestration only. It does
 * not implement bulk resolution, labels, refunds, disputes, or control activation, does not
 * invent a second distributed-queue implementation outside Spring Batch/PostgreSQL, and does not
 * write source tables directly outside the one reviewed domain-mutation seam.
 */
class ReviewBulkActionScopeBoundaryTests {

    private static final Path BULK_PACKAGE = Path.of("src", "main", "java", "com", "thaap",
            "payments", "bulk");

    /**
     * Fully-qualified high-risk services that would let a bulk item resolve, label, refund,
     * dispute, or activate a control/model if imported anywhere in the bulk package.
     */
    private static final List<String> PROHIBITED_IMPORTS = List.of(
            "com.thaap.payments.dispute.ChargebackService",
            "com.thaap.payments.dispute.DisputeOperationsService",
            "com.thaap.payments.payment.FullRefundService",
            "com.thaap.payments.payment.RefundController",
            "com.thaap.payments.review.RiskLabelController",
            "com.thaap.payments.review.FraudLabelRepository",
            "com.thaap.payments.controls.GovernedControlService",
            "com.thaap.payments.simulation.RiskSimulationService",
            "com.thaap.payments.simulation.RiskSimulationActivationGate");

    /** A generic command dispatch would let a new action type reach an untyped mutation. */
    private static final List<String> PROHIBITED_TOKENS = List.of(
            "Class.forName", "MethodHandle", "reflect.Method", "@SuppressWarnings(\"unchecked\")");

    @Test
    void theBulkPackageNeverImportsAHighRiskDomainService() throws IOException {
        for (Path javaFile : sourceFiles()) {
            String source = Files.readString(javaFile, StandardCharsets.UTF_8);
            for (String prohibited : PROHIBITED_IMPORTS) {
                assertThat(source).as("%s must not reference the high-risk service %s",
                        javaFile, prohibited).doesNotContain(prohibited);
            }
        }
    }

    @Test
    void theBulkPackageNeverBuildsAGenericReflectiveActionDispatch() throws IOException {
        for (Path javaFile : sourceFiles()) {
            String source = Files.readString(javaFile, StandardCharsets.UTF_8);
            for (String token : PROHIBITED_TOKENS) {
                assertThat(source).as("%s must not use %s to build a generic action dispatch",
                        javaFile, token).doesNotContain(token);
            }
        }
    }

    @Test
    void theDomainMutationInvokesOnlyAssignOrReleaseAndNeverResolve() throws IOException {
        String source = Files.readString(BULK_PACKAGE.resolve("ReviewBulkDomainMutation.java"),
                StandardCharsets.UTF_8);
        assertThat(source).contains("reviewOperationsService.assign(")
                .contains("reviewOperationsService.release(")
                .doesNotContain("reviewOperationsService.resolve(");
    }

    @Test
    void theBulkActionCodeVocabularyIsClosedToExactlyThreeAssignmentOperations() {
        assertThat(Stream.of(ReviewBulkActionCode.values()).map(Enum::name).toList())
                .containsExactlyInAnyOrder("ASSIGN_TO_SELF", "ASSIGN_TO_OPERATOR", "RELEASE");
    }

    @Test
    void theBulkControllerDeclaresNoMutationVerbBeyondItsThreeTypedRoutes() {
        for (Method method : ReviewBulkActionController.class.getDeclaredMethods()) {
            assertThat(method.isAnnotationPresent(PutMapping.class)).as(method.getName()).isFalse();
            assertThat(method.isAnnotationPresent(PatchMapping.class)).as(method.getName()).isFalse();
            assertThat(method.isAnnotationPresent(DeleteMapping.class)).as(method.getName()).isFalse();
        }
    }

    @Test
    void onlyTheStoreAndExecutionSeamTouchJdbcOrEntityManagerDirectly() throws IOException {
        for (Path javaFile : sourceFiles()) {
            String fileName = javaFile.getFileName().toString();
            if (fileName.equals("ReviewBulkActionStore.java")
                    || fileName.equals("ReviewBulkBatchConfiguration.java")
                    || fileName.equals("ReviewBulkExecutionConfiguration.java")) {
                continue;
            }
            String source = Files.readString(javaFile, StandardCharsets.UTF_8);
            assertThat(source).as("%s must not bypass the reviewed store with direct JDBC access",
                    javaFile).doesNotContain("JdbcTemplate").doesNotContain("EntityManager");
        }
    }

    @Test
    void theBulkSurfaceOwnsNoAggregateOrInsightRoute() throws IOException {
        // Queue productivity and outcome aggregates belong to the insights capability. If the
        // bulk surface grew its own summary route, two owners would publish the same number
        // under different as-of semantics and an analyst could not tell which one to believe.
        for (Path javaFile : sourceFiles()) {
            String source = Files.readString(javaFile, StandardCharsets.UTF_8);
            for (String aggregate : List.of("/insights", "/summary", "/statistics",
                    "/metrics", "/aggregate")) {
                assertThat(source).as("%s declares the aggregate route %s", javaFile, aggregate)
                        .doesNotContain("\"" + aggregate);
            }
            assertThat(source).as("%s imports the insights capability", javaFile)
                    .doesNotContain("com.thaap.payments.insights");
        }
    }

    @Test
    void theInsightCapabilityLikewiseOwnsNoBulkMutationRoute() throws IOException {
        Path insightsPackage = Path.of("src", "main", "java", "com", "thaap", "payments",
                "insights");
        try (Stream<Path> paths = Files.list(insightsPackage)) {
            List<Path> files = paths.filter(path -> path.toString().endsWith(".java")).toList();
            assertThat(files).as("the reciprocal scan is vacuous with no insight sources")
                    .isNotEmpty();
            for (Path javaFile : files) {
                String source = Files.readString(javaFile, StandardCharsets.UTF_8);
                assertThat(source).as("%s reaches into bulk assignment", javaFile)
                        .doesNotContain("com.thaap.payments.bulk")
                        .doesNotContain("ReviewBulkActionService");
                assertThat(source).as("%s declares a mutation mapping", javaFile)
                        .doesNotContain("@PostMapping").doesNotContain("@PutMapping")
                        .doesNotContain("@DeleteMapping");
            }
        }
    }

    @Test
    void thePublicDemoDrivesTheProductOnlyThroughItsPublishedRoutesAndShowsAMixedResult()
            throws IOException {
        Path demo = Path.of("..", "..").normalize()
                .resolve("scripts/demo-saved-views-and-bulk-review.py");
        assertThat(Files.exists(demo))
                .as("the chapter claims a public saved-view and bulk demo, so one must exist")
                .isTrue();
        String source = Files.readString(demo, StandardCharsets.UTF_8);

        // Reconciliation may read the database, but every product effect travels HTTP.
        assertThat(source).contains("accepts only SELECT");
        assertThat(source.indexOf("subprocess.run")).isEqualTo(source.lastIndexOf("subprocess.run"));
        assertThat(source).contains("/risk/workspace/payment-views")
                .contains("/risk/workspace/review-bulk-actions");

        // A bulk demo that only shows success has not demonstrated per-item truth.
        assertThat(source).contains("PARTIAL").contains("STALE").contains("NOT_FOUND");

        for (String outOfScope : List.of("/risk/workspace/insights", "/resolve", "/refunds",
                "/disputes", "/risk/controls")) {
            assertThat(source).as("the demo reaches out-of-scope surface %s", outOfScope)
                    .doesNotContain("\"" + outOfScope);
        }
    }

    private List<Path> sourceFiles() throws IOException {
        try (Stream<Path> paths = Files.list(BULK_PACKAGE)) {
            return paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }
}
