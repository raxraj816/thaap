package com.thaap.payments.support;

import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL container and one Spring context for every decision-intelligence workspace
 * proof, across packages.
 *
 * <p>`ER-0094` and `ER-0095` both record the same failure: a class that declares its own
 * `@Container` costs the suite a whole database and a whole application context, and past a
 * threshold an unrelated class starts failing to resolve a test-scoped bean. `ER-0095` restated
 * the rule as a property of the risk rather than of one package, which is why this base lives in
 * `support` instead of beside any one capability — a second capability needing PostgreSQL must be
 * able to reuse this container rather than mint another.
 *
 * <p>Because every subclass shares the same `@SpringBootTest` configuration, profile, and
 * dynamic properties, Spring's context cache serves them all from one context. Adding a subclass
 * therefore costs nothing.
 *
 * <p>The container is a manually managed singleton rather than a `@Container` field on purpose:
 * `@Container` on a static field is started and stopped per test class, so the second subclass
 * would receive a fresh container on a new port while the cached context still addressed the
 * stopped one.
 *
 * <p>Subclasses share database state, so each must clean in its own `@BeforeEach`.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "thaap.review-bulk.worker-enabled=false")
@EnabledIf("com.thaap.payments.support.WorkspacePostgreSqlTestBase#dockerAvailable")
public abstract class WorkspacePostgreSqlTestBase {

    private static final PostgreSQLContainer POSTGRES = startIfDockerAvailable();

    public static boolean dockerAvailable() {
        return POSTGRES != null;
    }

    private static PostgreSQLContainer startIfDockerAvailable() {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            return null;
        }
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:16-alpine");
        container.start();
        return container;
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
}
