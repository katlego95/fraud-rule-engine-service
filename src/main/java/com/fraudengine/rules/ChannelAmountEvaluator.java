package com.fraudengine.rules;

import com.fraudengine.domain.Channel;
import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleOutcome;
import com.fraudengine.domain.RuleType;
import com.fraudengine.domain.TransactionEvent;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
class ChannelAmountEvaluator implements RuleEvaluator {

    record Parameters(Channel channel, BigDecimal threshold) {}

    private final RuleParameters parameters;

    ChannelAmountEvaluator(RuleParameters parameters) {
        this.parameters = parameters;
    }

    @Override
    public RuleType type() {
        return RuleType.CHANNEL_AMOUNT;
    }

    @Override
    public Class<?> parametersType() {
        return Parameters.class;
    }

    @Override
    public RuleOutcome evaluate(Rule rule, TransactionEvent event) {
        Parameters params = parameters.read(rule, Parameters.class);

        if (event.channel() != params.channel()) {
            return RuleOutcome.notMatched(rule, "Channel %s is not %s".formatted(event.channel(), params.channel()));
        }
        if (event.amount().compareTo(params.threshold()) <= 0) {
            return RuleOutcome.notMatched(rule, "%s amount %s is within the %s threshold"
                    .formatted(params.channel(), Money.format(event.amount()), Money.format(params.threshold())));
        }
        return RuleOutcome.matched(rule, "%s amount %s is above the %s threshold"
                .formatted(params.channel(), Money.format(event.amount()), Money.format(params.threshold())));
    }
}
