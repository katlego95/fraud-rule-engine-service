package com.fraudengine;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL container shared across the whole integration suite. Integration tests run
 * against a real database rather than an in-memory substitute because the things worth testing
 * here — JSONB, partial indexes, keyset pagination, constraint behaviour — are precisely the
 * things a substitute reproduces differently or not at all.
 *
 * <p>Started here rather than through the {@code @Testcontainers} extension: that extension ties
 * a container's lifecycle to the test class it is declared on, so a container shared through a
 * base class is stopped when the first subclass finishes and every later class connects to a
 * closed port. Ryuk reaps this one when the JVM exits.
 */
@SpringBootTest
public abstract class PostgresIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    static {
        POSTGRES.start();
    }
}
