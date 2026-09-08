package com.fraudengine.rules;

import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleOutcome;
import com.fraudengine.domain.RuleType;
import com.fraudengine.domain.TransactionEvent;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Card testing spread across merchants to stay under any single merchant's velocity limit. */
@Component
class MerchantSpreadVelocityEvaluator implements RuleEvaluator {

    record Parameters(int maxMerchants, int windowMinutes) {}

    private final RuleParameters parameters;
    private final VelocityRepository velocity;

    MerchantSpreadVelocityEvaluator(RuleParameters parameters, VelocityRepository velocity) {
        this.parameters = parameters;
        this.velocity = velocity;
    }

    @Override
    public RuleType type() {
        return RuleType.MERCHANT_SPREAD_VELOCITY;
    }

    @Override
    public Class<?> parametersType() {
        return Parameters.class;
    }

    @Override
    public RuleOutcome evaluate(Rule rule, TransactionEvent event) {
        Parameters params = parameters.read(rule, Parameters.class);
        Instant windowStart = event.occurredAt().minus(Duration.ofMinutes(params.windowMinutes()));

        long merchants = velocity.countDistinctMerchants(event.cardToken(), windowStart, event.occurredAt());

        String observation = "%d distinct merchants on this card in %d minutes (including this one), threshold %d"
                .formatted(merchants, params.windowMinutes(), params.maxMerchants());

        return merchants > params.maxMerchants()
                ? RuleOutcome.matched(rule, observation)
                : RuleOutcome.notMatched(rule, observation);
    }
}
