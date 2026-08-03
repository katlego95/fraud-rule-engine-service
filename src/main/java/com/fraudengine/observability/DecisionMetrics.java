package com.fraudengine.observability;

import com.fraudengine.domain.Decision;
import com.fraudengine.domain.OutcomeStatus;
import com.fraudengine.domain.RuleOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Decision latency is a business metric here, not an operational one: the service sits in the
 * authorisation path, so a slow decision is a decision that arrives after the authorisation it was
 * meant to inform.
 */
@Component
public class DecisionMetrics {

    private final MeterRegistry registry;
    private final Timer evaluationTimer;

    DecisionMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.evaluationTimer = Timer.builder("fraud.decision.evaluation")
                .description("Time to evaluate one transaction against the active rule set")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .maximumExpectedValue(Duration.ofMillis(500))
                .register(registry);
    }

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    public void recordEvaluated(Timer.Sample sample, Decision decision) {
        sample.stop(evaluationTimer);

        registry.counter("fraud.decision.verdict", "verdict", decision.verdict().name()).increment();

        for (RuleOutcome outcome : decision.outcomes()) {
            if (outcome.status() == OutcomeStatus.MATCHED) {
                registry.counter("fraud.rule.matched",
                        "rule", outcome.ruleCode(),
                        "mode", outcome.mode().name()).increment();
            } else if (outcome.status() == OutcomeStatus.NOT_EVALUABLE) {
                // Worth its own counter: a rule that silently stops being evaluable is a fraud
                // control that has quietly switched itself off.
                registry.counter("fraud.rule.not_evaluable", "rule", outcome.ruleCode()).increment();
            }
        }
    }

    public void recordReplayed() {
        registry.counter("fraud.decision.replayed").increment();
    }
}
