package com.thaap.payments.support;

import com.thaap.payments.PaymentsApiApplication;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Starts and stops real application instances against one database.
 *
 * <p>Every restart claim in this repository was previously proven by constructing the state a
 * dead process leaves behind, because no harness existed to produce that state honestly. This is
 * that harness: each {@link #start} builds a complete {@link PaymentsApiApplication} context and
 * each {@link #stop} destroys it. Beans are constructed and destroyed for real, schedulers and
 * executors start and shut down, in-memory worker identity is lost, and the second instance
 * rediscovers everything it needs from the database — which is exactly what a rolling deployment
 * or a crash-and-restart does.
 *
 * <p>The database outlives the instances by design. A restart that also reset the database would
 * prove nothing about resumption; the whole question is what the next process can recover from
 * what the last one committed.
 */
public final class ApplicationRestartHarness implements AutoCloseable {

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final List<Class<?>> extraSources = new ArrayList<>();
    private WebApplicationType webApplicationType = WebApplicationType.NONE;
    private ConfigurableApplicationContext context;

    public ApplicationRestartHarness(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    /**
     * Registers an additional configuration class present in every instance this harness starts.
     * Used to install a test seam that must exist inside the application under test rather than
     * beside it.
     */
    public ApplicationRestartHarness withConfiguration(Class<?> configuration) {
        extraSources.add(configuration);
        return this;
    }

    /**
     * Starts instances with the servlet stack so a test can drive them over the real HTTP
     * surface. The port is ephemeral because nothing dials in from outside; what this buys is a
     * web application context, which is what lets a restart test build its fixture through the
     * same controllers a real deployment serves rather than through hand-written SQL.
     */
    public ApplicationRestartHarness withWebLayer() {
        this.webApplicationType = WebApplicationType.SERVLET;
        return this;
    }

    /** Starts a fresh instance, stopping any previous one first. */
    public ConfigurableApplicationContext start(String... properties) {
        stop();
        List<Class<?>> sources = new ArrayList<>();
        sources.add(PaymentsApiApplication.class);
        sources.addAll(extraSources);
        List<String> arguments = new ArrayList<>(List.of(
                "--spring.profiles.active=test",
                "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.username=" + username,
                "--spring.datasource.password=" + password,
                "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--server.port=0"));
        for (String property : properties) {
            arguments.add("--" + property);
        }
        context = new SpringApplicationBuilder(sources.toArray(Class<?>[]::new))
                .web(webApplicationType)
                .run(arguments.toArray(String[]::new));
        return context;
    }

    /**
     * Destroys the running instance. Returns normally even when shutdown itself fails: a process
     * that dies mid-work does not shut down cleanly either, and a harness that insisted on a
     * clean stop could not model the case these tests exist for.
     */
    public void stop() {
        if (context == null) {
            return;
        }
        try {
            context.close();
        } catch (RuntimeException ignored) {
            // A dying process does not get to complete its shutdown hooks.
        } finally {
            context = null;
        }
    }

    @Override
    public void close() {
        stop();
    }
}
