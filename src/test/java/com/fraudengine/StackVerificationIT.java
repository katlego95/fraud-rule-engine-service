package com.fraudengine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Proves the parts of the stack that cannot be verified by resolution alone: that Flyway applies
 * against a real PostgreSQL, and that a JSONB column round-trips through JdbcClient. Both are
 * load-bearing for later phases and both fail in ways an in-memory database would not reproduce.
 */
class StackVerificationIT extends PostgresIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void migrationsApplyCleanly() {
        Long failures = jdbc.sql("select count(*) from flyway_schema_history where success = false")
                .query(Long.class)
                .single();

        assertThat(failures).isZero();
    }

    @Test
    void jsonbParametersRoundTrip() {
        UUID id = UUID.randomUUID();

        jdbc.sql("""
                insert into rules (id, code, version, type, mode, nature, weight, parameters,
                                   description, typology, created_at)
                values (:id, 'JSONB_PROBE', 1, 'THRESHOLD', 'DISABLED', 'CONTRIBUTORY', 10,
                        cast(:parameters as jsonb), 'Stack verification probe', 'none', :createdAt)
                """)
                .param("id", id)
                .param("parameters", "{\"threshold\":\"15000.50\",\"currencies\":[\"ZAR\"]}")
                .param("createdAt", OffsetDateTime.now())
                .update();

        String threshold = jdbc.sql("select parameters ->> 'threshold' from rules where id = :id")
                .param("id", id)
                .query(String.class)
                .single();

        assertThat(threshold).isEqualTo("15000.50");

        // The container is shared across the suite, so the probe cleans up rather than leaving a
        // ninth rule for the rule repository tests to count.
        jdbc.sql("delete from rules where id = :id").param("id", id).update();
    }

    @Test
    void natureConstraintRejectsARuleThatIsBothDecisiveAndWeighted() {
        assertThat(insertFails("DECISIVE", "BLOCK", 25)).isTrue();
        assertThat(insertFails("CONTRIBUTORY", "BLOCK", null)).isTrue();
    }

    private boolean insertFails(String nature, String verdict, Integer weight) {
        try {
            jdbc.sql("""
                    insert into rules (id, code, version, type, mode, nature, verdict, weight,
                                       description, typology, created_at)
                    values (:id, :code, 1, 'THRESHOLD', 'DISABLED', :nature, :verdict, :weight,
                            'Constraint probe', 'none', :createdAt)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("code", "CONSTRAINT_PROBE_" + nature + "_" + weight)
                    .param("nature", nature)
                    .param("verdict", verdict)
                    .param("weight", weight)
                    .param("createdAt", OffsetDateTime.now())
                    .update();
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }
}
