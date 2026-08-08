package com.thaap.payments.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a test class owns the {@code MeterRegistry} it writes to.
 *
 * <p>Spring caches one application context — and therefore one registry bean — per distinct test
 * configuration, and hands that same instance to every class whose configuration matches. A class
 * that empties or prunes the registry in setup is therefore not resetting its own state; it is
 * writing to state that dozens of other classes are reading. Meters that are re-registered on each
 * use grow back and hide it, so the damage is visible only to a meter registered exactly once, and
 * only in the orderings that run the pruning class first.
 *
 * <p><strong>Mechanism.</strong> This annotation adds a {@code ContextCustomizer} whose identity is
 * the annotated class, which makes the context cache key unique to that class. The effect is
 * therefore broader than the name: the class gets its own application context, not merely its own
 * registry. That cost — one extra context — is the price of an honest assertion, and it is the same
 * mechanism Spring itself uses to isolate a class that declares {@code @MockitoSpyBean}.
 *
 * <p>Enforced by {@code MeterRegistryIsolationTests}, which fails the build when a
 * {@code @SpringBootTest} class prunes a registry without declaring this. See {@code ER-0101}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
@Documented
public @interface ExclusiveMeterRegistry {
}
