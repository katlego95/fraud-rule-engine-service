package com.fraudengine.decision;

import com.fraudengine.domain.Decision;
import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleOutcome;
import com.fraudengine.domain.TransactionEvent;
import com.fraudengine.rules.RuleEngine;
import com.fraudengine.rules.RuleRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class DecisionService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DecisionService.class);

    private final TransactionEventRepository events;
    private final DecisionRepository decisions;
    private final RuleRepository rules;
    private final RuleEngine ruleEngine;
    private final CompositionEngine compositionEngine;
    private final PayloadHash payloadHash;
    private final EventPrivacy privacy;
    private final com.fraudengine.observability.DecisionMetrics metrics;
    private final JsonMapper json;
    private final Clock clock;
    private final String engineVersion;

    DecisionService(TransactionEventRepository events, DecisionRepository decisions,
            RuleRepository rules, RuleEngine ruleEngine, CompositionEngine compositionEngine,
            PayloadHash payloadHash, EventPrivacy privacy,
            com.fraudengine.observability.DecisionMetrics metrics, JsonMapper json, Clock clock,
            @Value("${fraud.engine-version}") String engineVersion) {
        this.events = events;
        this.decisions = decisions;
        this.rules = rules;
        this.ruleEngine = ruleEngine;
        this.compositionEngine = compositionEngine;
        this.payloadHash = payloadHash;
        this.privacy = privacy;
        this.metrics = metrics;
        this.json = json;
        this.clock = clock;
        this.engineVersion = engineVersion;
    }

    /**
     * Evaluates one transaction, or returns the decision an earlier submission of the same event
     * identifier already produced.
     *
     * <p>The event and its decision are written in one transaction. Split across two, a crash
     * between them would leave an event that is "already seen" with no decision to return, and
     * every retry would take the replay path and find nothing — poisoning that identifier
     * permanently.
     */
    @Transactional
    public DecisionResult decide(TransactionEvent event) {
        String hash = payloadHash.of(event);

        // Device and IP identifiers are hashed before they touch the database; nothing queries
        // them, so plaintext buys nothing and costs blast radius. See ADR 0005.
        if (!events.insertIfAbsent(privacy.forStorage(event), hash)) {
            metrics.recordReplayed();
            return replay(event, hash);
        }

        io.micrometer.core.instrument.Timer.Sample sample = metrics.start();
        List<Rule> evaluable = rules.findEvaluable();
        List<RuleOutcome> outcomes = ruleEngine.evaluate(evaluable, event);
        Composition composition = compositionEngine.compose(outcomes);

        // Truncated to the precision PostgreSQL timestamptz actually stores. Without this, the
        // decision returned from an evaluation carries nanoseconds that the stored row does not,
        // so a caller who posts a transaction and then reads it back sees two different
        // timestamps for one decision. Only visible where the platform clock has nanosecond
        // granularity, which is why it survived development on macOS and failed on Linux.
        Instant evaluatedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);

        Decision decision = new Decision(
                UUID.randomUUID(),
                event.eventId(),
                event.accountId(),
                event.cardToken(),
                event.occurredAt(),
                evaluatedAt,
                composition.finalVerdict(),
                composition.decisiveVerdict(),
                composition.scoreVerdict(),
                composition.totalScore(),
                composition.bands().reviewFrom(),
                composition.bands().blockFrom(),
                engineVersion,
                json.writeValueAsString(privacy.forStorage(event)),
                outcomes);

        decisions.insert(decision);
        metrics.recordEvaluated(sample, decision);

        // The card token masks itself, so this cannot leak one however the line is edited later.
        log.info("Decision {} for event {}: {} (score {}) on card {}", decision.decisionId(),
                decision.eventId(), decision.verdict(), decision.totalScore(), event.cardToken());

        return new DecisionResult(decision, false);
    }

    private DecisionResult replay(TransactionEvent event, String hash) {
        String stored = events.payloadHashOf(event.eventId())
                .orElseThrow(() -> new IllegalStateException(
                        "Event %s conflicted on insert but has no stored row".formatted(event.eventId())));

        if (!stored.equals(hash)) {
            throw new IdempotencyConflictException(event.eventId());
        }

        return decisions.findByEventId(event.eventId())
                .map(decision -> new DecisionResult(decision, true))
                .orElseThrow(() -> new IllegalStateException(
                        "Event %s exists without a decision".formatted(event.eventId())));
    }
}
