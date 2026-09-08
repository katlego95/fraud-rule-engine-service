package com.fraudengine.rules;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Carries a rule change to the instances that did not make it.
 *
 * <p>The in-process listener on {@link RuleSnapshot} refreshes the instance that wrote the rule.
 * Every other instance would wait for its scheduled refresh, so behind a load balancer a rule
 * disabled during an attack keeps being applied by most of the traffic until that fires. PostgreSQL
 * is the one thing all instances already talk to, so the write announces itself there and everyone
 * hears it.
 *
 * <p><strong>Its own connection, outside the pool.</strong> A pooled connection is handed back
 * after each statement and a returned connection stops listening. Taking one from the pool and
 * never returning it would also quietly cost the decision path a connection, so this opens its own
 * and is not counted against {@code maximum-pool-size}.
 *
 * <p><strong>Reconnects.</strong> A dropped connection means notifications are missed silently,
 * which is the failure the scheduled refresh exists to bound — but bounding it is not the same as
 * repairing it, so the loop reconnects rather than giving up.
 */
@Component
class RuleChangeListener {

    static final String CHANNEL = "rules_changed";

    private static final Logger log = LoggerFactory.getLogger(RuleChangeListener.class);
    private static final long POLL_TIMEOUT_MS = 10_000;
    private static final long RECONNECT_DELAY_MS = 5_000;

    private final RuleSnapshot snapshot;
    private final String url;
    private final String username;
    private final String password;

    private volatile boolean running = true;
    private volatile Thread thread;

    /**
     * Credentials come from the configured pool rather than from properties. The two are not
     * always the same: a test binds the DataSource to a container without ever setting
     * {@code spring.datasource.url}, so reading the property would connect this listener to a
     * different database than the one the service is using — and it would look like it worked.
     */
    RuleChangeListener(RuleSnapshot snapshot, DataSource dataSource) throws SQLException {
        this.snapshot = snapshot;
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        this.url = pool.getJdbcUrl();
        this.username = pool.getUsername();
        this.password = pool.getPassword();
    }

    /**
     * Started once the application is ready rather than during construction, so a listener cannot
     * refresh a snapshot the context has not finished building.
     */
    @EventListener(ApplicationReadyEvent.class)
    void start() {
        thread = Thread.ofVirtual().name("rule-change-listener").start(this::listen);
    }

    @PreDestroy
    void stop() {
        running = false;
        Thread current = thread;
        if (current != null) {
            current.interrupt();
        }
    }

    private void listen() {
        while (running) {
            try (Connection connection = DriverManager.getConnection(url, username, password)) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("listen " + CHANNEL);
                }
                log.info("Listening for rule changes on '{}'", CHANNEL);
                awaitNotifications(connection.unwrap(PGConnection.class));
            } catch (Exception e) {
                if (running) {
                    log.warn("Rule change listener disconnected; retrying in {}ms. The scheduled "
                            + "refresh bounds staleness until it returns.", RECONNECT_DELAY_MS, e);
                    sleep(RECONNECT_DELAY_MS);
                }
            }
        }
    }

    private void awaitNotifications(PGConnection connection) throws Exception {
        while (running) {
            // Blocks until PostgreSQL delivers something or the timeout expires. The timeout is
            // what lets the loop notice it has been asked to stop, and what surfaces a connection
            // that has died without saying so.
            PGNotification[] notifications = connection.getNotifications((int) POLL_TIMEOUT_MS);
            if (notifications != null && notifications.length > 0) {
                // One refresh however many arrived: they all ask the same question.
                log.info("Rule change announced by another instance, refreshing snapshot");
                snapshot.refresh();
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
