package com.fraudengine.rules;

import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleOutcome;
import com.fraudengine.domain.RuleType;
import com.fraudengine.domain.TransactionEvent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Account takeover and cash-out: sustained drawdown rather than a single anomalous amount. */
@Component
class AccountAmountVelocityEvaluator implements RuleEvaluator {

    record Parameters(BigDecimal maxAmount, int windowHours) {}

    private final RuleParameters parameters;
    private final VelocityRepository velocity;

    AccountAmountVelocityEvaluator(RuleParameters parameters, VelocityRepository velocity) {
        this.parameters = parameters;
        this.velocity = velocity;
    }

    @Override
    public RuleType type() {
        return RuleType.ACCOUNT_AMOUNT_VELOCITY;
    }

    @Override
    public Class<?> parametersType() {
        return Parameters.class;
    }

    @Override
    public RuleOutcome evaluate(Rule rule, TransactionEvent event) {
        Parameters params = parameters.read(rule, Parameters.class);
        Instant windowStart = event.occurredAt().minus(Duration.ofHours(params.windowHours()));

        BigDecimal total = velocity.sumAccountAmount(event.accountId(), windowStart, event.occurredAt());

        String observation = "%s on this account in %d hours (including this transaction), threshold %s"
                .formatted(Money.format(total), params.windowHours(), Money.format(params.maxAmount()));

        return total.compareTo(params.maxAmount()) > 0
                ? RuleOutcome.matched(rule, observation)
                : RuleOutcome.notMatched(rule, observation);
    }
}
