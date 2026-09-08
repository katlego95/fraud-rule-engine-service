package com.fraudengine;

import com.fraudengine.rules.RuleChangedEvent;
import com.fraudengine.rules.RuleSnapshot;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.transaction.AfterTransaction;
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
@Import(PostgresIntegrationTest.RefreshSnapshotOnWrite.class)
public abstract class PostgresIntegrationTest {

    /**
     * Stands in for the commit that a rolled-back test never reaches.
     *
     * <p>In production the rule snapshot refreshes on an AFTER_COMMIT listener, which is correct:
     * refreshing inside the writing transaction would cache rules a rollback erases. Tests here
     * are {@code @Transactional} and roll back, so that listener never fires and a test that
     * changes a rule and then decides would evaluate the pre-change set.
     *
     * <p>This listener is not transactional and fires immediately, giving those tests the
     * behaviour a committed write would have. The production path is proved separately by
     * {@code RuleSnapshotIT}, which commits for real.
     */
    @Autowired
    private RuleSnapshot ruleSnapshot;

    /**
     * The snapshot is a singleton and outlives the test transaction, so a rule created by a
     * rolled-back test would stay cached and the next test would evaluate a rule whose row no
     * longer exists — the decision then fails a foreign key writing its outcome. Refreshing after
     * the rollback puts the snapshot back to the committed baseline.
     *
     * <p>Production does not have this shape: the listener refreshes on commit, and rules are
     * never deleted.
     */
    @AfterTransaction
    void restoreSnapshotToCommittedState() {
        ruleSnapshot.refresh();
    }

    @TestConfiguration
    static class RefreshSnapshotOnWrite {

        private final RuleSnapshot snapshot;

        RefreshSnapshotOnWrite(RuleSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @EventListener
        void onRuleChanged(RuleChangedEvent event) {
            snapshot.refresh();
        }
    }

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    static {
        POSTGRES.start();
    }
}
