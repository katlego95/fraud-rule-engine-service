package com.fraudengine.rules;

import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleOutcome;
import com.fraudengine.domain.RuleType;
import com.fraudengine.domain.TransactionEvent;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Card testing: an attacker holding stolen card numbers runs small transactions in rapid
 * succession to find which remain live. Velocity on authorisation attempts is the primary signal.
 */
@Component
class CardCountVelocityEvaluator implements RuleEvaluator {

    record Parameters(int maxCount, int windowMinutes) {}

    private final RuleParameters parameters;
    private final VelocityRepository velocity;

    CardCountVelocityEvaluator(RuleParameters parameters, VelocityRepository velocity) {
        this.parameters = parameters;
        this.velocity = velocity;
    }

    @Override
    public RuleType type() {
        return RuleType.CARD_COUNT_VELOCITY;
    }

    @Override
    public Class<?> parametersType() {
        return Parameters.class;
    }

    @Override
    public RuleOutcome evaluate(Rule rule, TransactionEvent event) {
        Parameters params = parameters.read(rule, Parameters.class);
        Instant windowStart = event.occurredAt().minus(Duration.ofMinutes(params.windowMinutes()));

        long count = velocity.countCardTransactions(event.cardToken(), windowStart, event.occurredAt());

        String observation = "%d transactions on this card in %d minutes (including this one), threshold %d"
                .formatted(count, params.windowMinutes(), params.maxCount());

        return count > params.maxCount()
                ? RuleOutcome.matched(rule, observation)
                : RuleOutcome.notMatched(rule, observation);
    }
}
