package com.fraudengine.rules;

import com.fraudengine.domain.Channel;
import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleOutcome;
import com.fraudengine.domain.RuleType;
import com.fraudengine.domain.TransactionEvent;
import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * An amount threshold that applies only on certain channels.
 *
 * <p>A set rather than a single channel, because the typologies this serves are named for
 * categories and a category is usually more than one channel. Card-not-present is `ECOMMERCE` and
 * `TRANSFER`; expressed as one channel, a rule called `CNP_HIGH_AMOUNT` silently covered half of
 * what its name claimed. Keeping the set in the rule's parameters rather than deriving it from
 * {@link Channel#impliesPhysicalPresence()} leaves the grouping as data, so a rule can target any
 * combination without an evaluator change.
 */
@Component
class ChannelAmountEvaluator implements RuleEvaluator {

    record Parameters(Set<Channel> channels, BigDecimal threshold) {}

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

        if (!params.channels().contains(event.channel())) {
            return RuleOutcome.notMatched(rule, "Channel %s is not in %s"
                    .formatted(event.channel(), describe(params.channels())));
        }
        if (event.amount().compareTo(params.threshold()) <= 0) {
            return RuleOutcome.notMatched(rule, "%s amount %s is within the %s threshold"
                    .formatted(event.channel(), Money.format(event.amount()), Money.format(params.threshold())));
        }
        return RuleOutcome.matched(rule, "%s amount %s is above the %s threshold"
                .formatted(event.channel(), Money.format(event.amount()), Money.format(params.threshold())));
    }

    /** Ordered, so the reason a call centre agent reads does not change between decisions. */
    private static String describe(Set<Channel> channels) {
        return channels.stream().sorted().map(Enum::name).collect(Collectors.joining(", ", "[", "]"));
    }
}
