package com.fraudengine.rules;

import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleOutcome;
import com.fraudengine.domain.RuleType;
import com.fraudengine.domain.TransactionEvent;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
class AmountThresholdEvaluator implements RuleEvaluator {

    record Parameters(BigDecimal threshold) {}

    private final RuleParameters parameters;

    AmountThresholdEvaluator(RuleParameters parameters) {
        this.parameters = parameters;
    }

    @Override
    public RuleType type() {
        return RuleType.AMOUNT_THRESHOLD;
    }

    @Override
    public Class<?> parametersType() {
        return Parameters.class;
    }

    @Override
    public RuleOutcome evaluate(Rule rule, TransactionEvent event) {
        BigDecimal threshold = parameters.read(rule, Parameters.class).threshold();

        if (event.amount().compareTo(threshold) <= 0) {
            return RuleOutcome.notMatched(rule, "Amount %s is within the %s threshold"
                    .formatted(Money.format(event.amount()), Money.format(threshold)));
        }
        return RuleOutcome.matched(rule, "Amount %s is above the %s threshold"
                .formatted(Money.format(event.amount()), Money.format(threshold)));
    }
}
