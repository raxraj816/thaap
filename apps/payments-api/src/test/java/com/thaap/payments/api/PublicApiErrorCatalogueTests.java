package com.thaap.payments.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Proves C57-ERR-001 at the catalogue boundary. Clients branch on {@code error},
 * {@code retryable}, and {@code recoveryAction} and never on prose, so those three values
 * must be exact for every descriptor rather than for the handful anyone happened to test.
 *
 * <p>This class exists because a descriptor can be individually plausible and still lie in
 * aggregate: a permanent failure published as a transient one produces an endless client
 * retry loop and hides the underlying incident behind a backoff dashboard.
 */
class PublicApiErrorCatalogueTests {

    /** Statuses that promise the caller that waiting alone may change the answer. */
    private static final Set<HttpStatus> TRANSIENT_STATUSES = Set.of(
            HttpStatus.REQUEST_TIMEOUT, HttpStatus.TOO_MANY_REQUESTS,
            HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.GATEWAY_TIMEOUT);

    private static final Pattern STABLE_CODE = Pattern.compile("[a-z][a-z0-9]*(_[a-z0-9]+)*");
    private static final Pattern STABLE_RECOVERY_ACTION =
            Pattern.compile("[a-z][a-z0-9]*(_[a-z0-9]+)*");

    /**
     * Text that would leak internals or force a client to read prose to recover. The exception
     * clause is case-sensitive on purpose: "safety exception" is a governed-controls noun, while
     * {@code SomethingException} is a Java type escaping into the public contract.
     */
    private static final Pattern UNSAFE_MESSAGE = Pattern.compile(
            "(?i).*(select |insert |update |delete |from [a-z_]+ where|jdbc|sqlstate|"
            + "org\\.springframework|java\\.lang|java\\.sql|(?-i:[A-Za-z]+Exception)|stacktrace|"
            + "constraint \"|null pointer|@[a-z0-9.-]+\\.[a-z]{2,}|\\bpassword\\b|"
            + "\\btoken\\b|127\\.0\\.0\\.1|localhost:).*");

    @Test
    void aTransientClassificationIsNeverGivenToAPermanentFailure() {
        // 503 tells a client "wait and send exactly this again". A descriptor that says that
        // while also saying the request is not retryable is telling the client two things.
        List<String> dishonest = descriptors()
                .filter(descriptor -> descriptor.status() == HttpStatus.SERVICE_UNAVAILABLE)
                .filter(descriptor -> !descriptor.retryable())
                .map(PublicApiErrorDescriptor::code).toList();
        assertThat(dishonest)
                .as("a service-unavailable code that cannot be retried is misclassified")
                .isEmpty();

        List<String> misplacedRetry = descriptors()
                .filter(PublicApiErrorDescriptor::retryable)
                .filter(descriptor -> !TRANSIENT_STATUSES.contains(descriptor.status()))
                .map(descriptor -> descriptor.code() + "=" + descriptor.status().value()).toList();
        assertThat(misplacedRetry)
                .as("only a transient status may promise that an identical replay can succeed")
                .isEmpty();
    }

    @Test
    void everyRetryableFailureTellsTheClientHowToReplaySafely() {
        List<String> silent = descriptors().filter(PublicApiErrorDescriptor::retryable)
                .filter(descriptor -> !descriptor.recoveryAction().contains("retry"))
                .map(PublicApiErrorDescriptor::code).toList();
        assertThat(silent).as("a retryable failure must name the safe replay").isEmpty();

        // The dangerous inverse: a permanent failure whose recovery hint reads as "just retry"
        // would produce the same endless loop through the recovery field instead of the flag.
        List<String> falseInvitation = descriptors()
                .filter(descriptor -> !descriptor.retryable())
                .filter(descriptor -> descriptor.recoveryAction().startsWith("retry_")
                        && !descriptor.recoveryAction().contains("after_"))
                .map(PublicApiErrorDescriptor::code).toList();
        assertThat(falseInvitation)
                .as("a permanent failure must not invite an unchanged retry").isEmpty();
    }

    @Test
    void clientErrorsAreNeverRetryableUnlessWaitingIsTheDocumentedRemedy() {
        List<String> offenders = descriptors()
                .filter(descriptor -> descriptor.status().is4xxClientError())
                .filter(PublicApiErrorDescriptor::retryable)
                .filter(descriptor -> !TRANSIENT_STATUSES.contains(descriptor.status()))
                .map(PublicApiErrorDescriptor::code).toList();
        assertThat(offenders)
                .as("a 4xx means the client must change something before retrying").isEmpty();
    }

    @Test
    void serverErrorsNeverAskTheClientToFixARequestItGotRight() {
        List<String> offenders = descriptors()
                .filter(descriptor -> descriptor.status().is5xxServerError())
                .filter(descriptor -> descriptor.recoveryAction().contains("correct_the_request")
                        || descriptor.recoveryAction().contains("verify_the_")
                        || descriptor.recoveryAction().contains("use_a_"))
                .map(descriptor -> descriptor.code() + "=" + descriptor.recoveryAction()).toList();
        assertThat(offenders)
                .as("a server-side failure must not be blamed on the caller").isEmpty();
    }

    @Test
    void everyStableCodeIsUniqueMachineReadableAndMirrorsItsConstant() {
        List<PublicApiErrorDescriptor> all = descriptors().toList();
        assertThat(all).as("the catalogue is the whole public failure vocabulary")
                .hasSizeGreaterThan(100);

        Set<String> seen = new LinkedHashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (PublicApiErrorDescriptor descriptor : all) {
            if (!seen.add(descriptor.code())) {
                duplicates.add(descriptor.code());
            }
            assertThat(descriptor.code()).as("%s is machine-readable", descriptor.name())
                    .matches(STABLE_CODE);
            assertThat(descriptor.name())
                    .as("%s and its wire code cannot drift apart", descriptor.name())
                    .isEqualTo(descriptor.code().toUpperCase(Locale.ROOT));
            assertThat(descriptor.recoveryAction())
                    .as("%s publishes a machine-readable remedy", descriptor.name())
                    .isNotBlank().matches(STABLE_RECOVERY_ACTION);
            assertThat(descriptor.status().value())
                    .as("%s classifies as a failure", descriptor.name())
                    .isGreaterThanOrEqualTo(400);
        }
        // Two descriptors sharing one code make the value ambiguous for every client at once.
        assertThat(duplicates).as("stable codes are unique").isEmpty();
    }

    @Test
    void noSafeMessageLeaksInternalOrSensitiveDetail() {
        List<String> leaking = descriptors()
                .filter(descriptor -> UNSAFE_MESSAGE.matcher(descriptor.safeMessage()).matches())
                .map(descriptor -> descriptor.code() + ": " + descriptor.safeMessage()).toList();
        assertThat(leaking)
                .as("a reviewed message never carries SQL, framework, or credential detail")
                .isEmpty();

        List<String> empty = descriptors()
                .filter(descriptor -> descriptor.safeMessage().isBlank())
                .map(PublicApiErrorDescriptor::code).toList();
        assertThat(empty).as("every failure explains itself to a human").isEmpty();
    }

    @Test
    void theRecoveryVocabularyStaysSmallEnoughToBeImplementedByAClient() {
        Set<String> actions = descriptors().map(PublicApiErrorDescriptor::recoveryAction)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        // An unbounded recovery vocabulary is prose wearing a machine-readable costume: no client
        // can branch on it, so they would fall back to parsing message.
        assertThat(actions).as("the recovery vocabulary is a closed set clients can switch on")
                .hasSizeLessThanOrEqualTo(40);
    }

    @Test
    void twoDistinctOperationalCausesNeverShareOneCode() {
        // Integrity failure of stored evidence and a missing safe presentation both stop a
        // timeline read, but one is a tamper signal on immutable history and the other is a
        // release gap a later deploy fixes. One code for both would make them indistinguishable
        // to an operator and would force one retry answer onto two different truths.
        PublicApiErrorDescriptor permanent =
                PublicApiErrorDescriptor.TIMELINE_EVIDENCE_INTEGRITY_FAILED;
        PublicApiErrorDescriptor transientGap =
                PublicApiErrorDescriptor.TIMELINE_INTEGRITY_UNAVAILABLE;

        assertThat(permanent.code()).isNotEqualTo(transientGap.code());
        assertThat(permanent.retryable()).as("append-only history cannot be repaired").isFalse();
        assertThat(permanent.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(transientGap.retryable())
                .as("a missing presentation mapper is fixed by a deploy, not by the caller")
                .isTrue();
        assertThat(transientGap.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private java.util.stream.Stream<PublicApiErrorDescriptor> descriptors() {
        return Arrays.stream(PublicApiErrorDescriptor.values());
    }
}
