package com.thaap.payments.database;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL container for every pure-Flyway migration proof.
 *
 * <p>These classes start no Spring context — they drive Flyway directly against an isolated
 * schema — but each {@code @Container} field still costs the suite a whole database. The full
 * suite already runs the Spring-context PostgreSQL proofs against a memory-bounded Docker host,
 * and adding another container is enough to make an unrelated class fail to resolve a test-scoped
 * bean. Sharing one container here keeps migration proofs free to multiply.
 *
 * <p>The container is a manually managed singleton rather than a {@code @Container} field on
 * purpose: {@code @Container} on a static field is started and stopped per test class, so a
 * second subclass would get a fresh container on a new port. Started once and left running, it is
 * reclaimed by the Testcontainers Ryuk sidecar when the JVM exits.
 *
 * <p>Every subclass works inside its own generated schema, so they share the database safely.
 */
@EnabledIf("com.thaap.payments.database.MigrationPostgreSqlTestBase#dockerAvailable")
abstract class MigrationPostgreSqlTestBase {

    private static final PostgreSQLContainer POSTGRES = startIfDockerAvailable();

    static boolean dockerAvailable() {
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

    /** Creates an empty, uniquely named schema and returns a handle bound to it. */
    protected Database database(String prefix) {
        String schema = prefix + "_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource administrativeSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        new JdbcTemplate(administrativeSource).execute("CREATE SCHEMA " + schema);
        DriverManagerDataSource source = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
        return new Database(source, new JdbcTemplate(source), schema);
    }

    /** Migrates the schema, optionally stopping at {@code target} to stage a retained state. */
    protected void migrate(Database database, String target) {
        org.flywaydb.core.api.configuration.FluentConfiguration configuration = Flyway.configure()
                .dataSource(database.source()).schemas(database.schema())
                .defaultSchema(database.schema()).locations("classpath:db/migration");
        if (target != null) configuration.target(target);
        configuration.load().migrate();
    }

    protected void drop(Database database) {
        database.jdbc().execute("DROP SCHEMA IF EXISTS " + database.schema() + " CASCADE");
    }

    protected record Database(DriverManagerDataSource source, JdbcTemplate jdbc, String schema) {
    }
}
