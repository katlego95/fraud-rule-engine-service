package com.fraudengine.rules;

import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleOutcome;
import com.fraudengine.domain.RuleType;
import com.fraudengine.domain.TransactionEvent;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
class CountryBlocklistEvaluator implements RuleEvaluator {

    record Parameters(Set<String> countries) {}

    private final RuleParameters parameters;

    CountryBlocklistEvaluator(RuleParameters parameters) {
        this.parameters = parameters;
    }

    @Override
    public RuleType type() {
        return RuleType.COUNTRY_BLOCKLIST;
    }

    @Override
    public RuleOutcome evaluate(Rule rule, TransactionEvent event) {
        Set<String> blocked = parameters.read(rule, Parameters.class).countries();
        String country = event.merchantCountry();

        if (!blocked.contains(country)) {
            return RuleOutcome.notMatched(rule, "Merchant country %s is not blocked".formatted(country));
        }
        return RuleOutcome.matched(rule, "Merchant country %s is on the blocklist".formatted(country));
    }
}
