package com.thaap.payments.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Refuses to start a deployed instance whose security-relevant configuration is incomplete.
 *
 * <p>The failure this prevents is silent degradation, which is the worst shape a payment system's
 * failures can take. With an empty {@code THAAP_REVIEW_NOTE_ENCRYPTION_KEY} the application starts,
 * reports healthy, serves traffic, and writes analyst notes about named customers in a way the
 * product's own documentation describes as encrypted. Nothing is broken from the outside. The
 * damage is discovered later, by someone else, in a form that cannot be undone — the notes were
 * already written.
 *
 * <p>So the position taken here is the one {@code DomainEventProducerGate} already takes for event
 * emission, and it is the repository's settled answer: a component that cannot do its job must fail
 * loudly rather than serve a weaker promise than the one it advertises.
 *
 * <h2>Why this runs before the application context exists</h2>
 *
 * <p>An {@link EnvironmentPostProcessor} runs while the environment is being prepared — before any
 * bean is created, before Hikari opens a connection, and before Flyway takes the migration lock. A
 * {@code @PostConstruct} check would also stop the application, but only after it had touched the
 * production database. When the answer is "this instance must not run", the cheapest possible
 * moment to say so is before it has done anything at all.
 *
 * <h2>Why enforcement is a property rather than a profile check</h2>
 *
 * <p>{@code application-aws.yml} sets {@link #ENFORCEMENT_PROPERTY}. The gate reads the property
 * and knows nothing about profile names, which keeps one mechanism rather than two that can
 * disagree — and which matters for a product whose plan already anticipates more than one
 * deployment target and more than one region.
 */
public class RuntimeSecretContractGate implements EnvironmentPostProcessor, Ordered {

    /** Set by {@code application-aws.yml}; absent, and therefore false, everywhere else. */
    public static final String ENFORCEMENT_PROPERTY = "thaap.runtime.secret-contract-enforced";

    @Override
    public int getOrder() {
        // Last. Config data — application.yml, the active profile's file, and every environment
        // variable that overrides them — is imported by a post-processor ordered near the front,
        // and reading the environment before that has finished would be reading the wrong one.
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
            SpringApplication application) {
        if (!Boolean.TRUE.equals(
                environment.getProperty(ENFORCEMENT_PROPERTY, Boolean.class, Boolean.FALSE))) {
            return;
        }
        List<String> violations = violations(
                property -> environment.getProperty(property, String.class));
        if (!violations.isEmpty()) {
            throw new RuntimeSecretContractException(violations);
        }
    }

    /**
     * Every reason this configuration may not be deployed, in one pass.
     *
     * <p>Reported together rather than one at a time on purpose. An operator who has eight secrets
     * to supply and learns about them across eight failed deployments will, on the fourth, start
     * looking for the flag that turns the check off.
     */
    static List<String> violations(Function<String, String> resolver) {
        List<String> violations = new ArrayList<>();
        Map<String, RequiredRuntimeSecret> valueOwners = new LinkedHashMap<>();

        for (RequiredRuntimeSecret secret : RequiredRuntimeSecret.values()) {
            String value = resolver.apply(secret.property());
            Optional<String> violation = secret.violation(value);
            if (violation.isPresent()) {
                violations.add(secret.environmentVariable() + " (" + secret.property() + ") "
                        + violation.get() + " Generate one with: "
                        + secret.shape().generatorHint());
                continue;
            }

            // Distinctness. Reuse is not a shape problem — every reused value is individually
            // strong — but it collapses boundaries the system is built to keep apart. Sharing the
            // hash pepper with the correlation pepper ties credential rotation to fraud identity,
            // so a routine key roll would silently split every customer's history in two. Sharing
            // a KEK across three ciphers means one disclosure decrypts analyst notes, webhook
            // secrets and authentication replays together.
            RequiredRuntimeSecret existingOwner = valueOwners.putIfAbsent(value, secret);
            if (existingOwner != null) {
                violations.add(secret.environmentVariable() + " (" + secret.property()
                        + ") reuses the value set for " + existingOwner.environmentVariable()
                        + ". Each secret must be independently rotatable and independently "
                        + "compromisable. Generate a separate one with: "
                        + secret.shape().generatorHint());
            }
        }
        return violations;
    }

    /** Startup refusal carrying every reason at once, and none of the values. */
    static final class RuntimeSecretContractException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        RuntimeSecretContractException(List<String> violations) {
            super(message(violations));
        }

        private static String message(List<String> violations) {
            StringBuilder message = new StringBuilder()
                    .append("THAAP refuses to start: ")
                    .append(violations.size())
                    .append(violations.size() == 1 ? " runtime secret is" : " runtime secrets are")
                    .append(" not deployable. Starting anyway would mean encrypting nothing, "
                            + "authenticating nobody, or both, while reporting healthy.\n");
            for (String violation : violations) {
                message.append("  - ").append(violation).append('\n');
            }
            message.append("Every variable is documented in "
                    + "docs/references/RUNTIME_CONFIGURATION_REFERENCE.md.");
            return message.toString();
        }
    }
}
