package com.thaap.payments.support;

import java.util.List;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Gives every {@link ExclusiveMeterRegistry} class an application context of its own.
 *
 * <p>Registered through {@code META-INF/spring.factories}, the same way Spring registers the
 * factory that isolates classes declaring bean overrides.
 */
public class ExclusiveMeterRegistryContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass,
            List<ContextConfigurationAttributes> configurationAttributes) {
        return AnnotatedElementUtils.hasAnnotation(testClass, ExclusiveMeterRegistry.class)
                ? new ExclusiveRegistryOwner(testClass)
                : null;
    }

    /**
     * Carries no configuration, because identity is the whole point.
     *
     * <p>A context's cache key includes its customizers, and a record's equality is its components,
     * so one owner per class means one context per class. Nothing is customized in
     * {@link #customizeContext}; the context this participates in keying is the product.
     */
    record ExclusiveRegistryOwner(Class<?> owner) implements ContextCustomizer {

        @Override
        public void customizeContext(ConfigurableApplicationContext context,
                MergedContextConfiguration mergedConfig) {
            // Intentionally empty. See the class comment: this exists to be compared, not to run.
        }
    }
}
