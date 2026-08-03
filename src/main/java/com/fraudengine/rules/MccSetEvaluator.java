package com.fraudengine.rules;

import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleOutcome;
import com.fraudengine.domain.RuleType;
import com.fraudengine.domain.TransactionEvent;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
class MccSetEvaluator implements RuleEvaluator {

    record Parameters(Set<String> codes) {}

    private final RuleParameters parameters;

    MccSetEvaluator(RuleParameters parameters) {
        this.parameters = parameters;
    }

    @Override
    public RuleType type() {
        return RuleType.MCC_SET;
    }

    @Override
    public RuleOutcome evaluate(Rule rule, TransactionEvent event) {
        Set<String> codes = parameters.read(rule, Parameters.class).codes();
        String mcc = event.merchantCategoryCode();

        if (!codes.contains(mcc)) {
            return RuleOutcome.notMatched(rule, "Merchant category %s is not in the high-risk set".formatted(mcc));
        }
        return RuleOutcome.matched(rule, "Merchant category %s is in the high-risk set".formatted(mcc));
    }
}
