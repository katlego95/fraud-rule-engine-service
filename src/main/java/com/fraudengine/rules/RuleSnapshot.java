package com.fraudengine.rules;

import com.fraudengine.domain.Rule;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The evaluable rule set, held in memory.
 *
 * <p>Rules change perhaps once a minute and are read on every transaction, so reading them from
 * the database per decision asks the same question about fifteen hundred times a second and gets
 * the same answer. The set is a few kilobytes and identical on every instance, so each holding its
 * own copy is correct and free — unlike velocity counters, which have to be shared to be right.
 *
 * <p><strong>Consistency.</strong> Caching trades strict consistency for not asking. The trade is
 * bounded: a change refreshes every instance on commit, and {@link #refreshOnSchedule()} bounds
 * how long a lost signal can leave an instance stale. {@code fraud.rules.snapshot.age} reports
 * that age so a stale instance is visible rather than merely unlikely.
 *
 * <p><strong>Why after commit, not during.</strong> Refreshing inside the writing transaction
 * would read uncommitted rules, and a rollback would leave the cache holding rules that never
 * existed. The listener fires on commit, which is also when PostgreSQL delivers a notification —
 * so the same trigger extends across instances unchanged.
 */
@Component
public class RuleSnapshot implements EvaluableRules {

    private static final Logger log = LoggerFactory.getLogger(RuleSnapshot.class);

    private final RuleRepository rules;
    private final Clock clock;

    /**
     * Replaced whole, never edited. A decision sees either every rule from before the change or
     * every rule from after it, and volatile is what makes the swap visible to the threads
     * evaluating transactions rather than eventually.
     */
    private volatile List<Rule> current = List.of();

    private volatile Instant refreshedAt = Instant.EPOCH;

    RuleSnapshot(RuleRepository rules, Clock clock, MeterRegistry registry) {
        this.rules = rules;
        this.clock = clock;
        registry.gauge("fraud.rules.snapshot.age", this, RuleSnapshot::ageInSeconds);
        registry.gauge("fraud.rules.snapshot.size", this, snapshot -> snapshot.current.size());
    }

    /**
     * Loaded before the first decision, and fails the startup if it cannot be. An empty rule set
     * is not a degraded service, it is a service that approves everything: the same reasoning that
     * makes {@link RuleEngine} refuse to start when a rule type has no evaluator.
     */
    @PostConstruct
    void loadOrRefuseToStart() {
        List<Rule> loaded = rules.findEvaluable();
        if (loaded.isEmpty()) {
            throw new IllegalStateException(
                    "No evaluable rules were found at startup. Every transaction would be approved "
                            + "with no rule having run, so the application refuses to start.");
        }
        current = loaded;
        refreshedAt = clock.instant();
        log.info("Rule snapshot loaded with {} evaluable rules", loaded.size());
    }

    @Override
    public List<Rule> current() {
        return current;
    }

    /** A rule was written. Fires on commit, so a rolled-back write changes nothing. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onRuleChanged(RuleChangedEvent event) {
        log.info("Rule {} changed, refreshing snapshot", event.code());
        refresh();
    }

    /**
     * The backstop. A signal can be lost — a dropped connection, a restarted database — and an
     * instance serving rules that were withdrawn is the failure this bounds. Slow on purpose: the
     * event is the mechanism, this is the floor under it.
     */
    @Scheduled(fixedDelayString = "${fraud.rules.snapshot.refresh-interval-ms}")
    void refreshOnSchedule() {
        refresh();
    }

    /**
     * Keeps the last good set if the database is unreachable. A snapshot that empties itself on a
     * failed refresh would approve every transaction while reporting nothing wrong, which is worse
     * than being a minute out of date.
     */
    public void refresh() {
        try {
            List<Rule> loaded = rules.findEvaluable();
            if (loaded.isEmpty()) {
                log.error("Refresh returned no evaluable rules; keeping the previous {}", current.size());
                return;
            }
            current = loaded;
            refreshedAt = clock.instant();
        } catch (RuntimeException e) {
            log.error("Rule snapshot refresh failed; still serving {} rules from {}",
                    current.size(), refreshedAt, e);
        }
    }

    private double ageInSeconds() {
        return Duration.between(refreshedAt, clock.instant()).toMillis() / 1000.0;
    }
}
