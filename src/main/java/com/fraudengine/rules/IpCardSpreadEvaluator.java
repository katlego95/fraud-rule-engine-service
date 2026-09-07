package com.fraudengine.rules;

import com.fraudengine.decision.EventPrivacy;
import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleOutcome;
import com.fraudengine.domain.RuleType;
import com.fraudengine.domain.TransactionEvent;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Card testing from one source: an attacker runs a list of stolen numbers through a single
 * machine to find which are live. The signal is the spread of cards, not the volume of attempts —
 * a shared household or office address produces many transactions on few cards.
 */
@Component
class IpCardSpreadEvaluator implements RuleEvaluator {

    record Parameters(int maxCount, int windowMinutes) {}

    private final RuleParameters parameters;
    private final VelocityRepository velocity;
    private final EventPrivacy privacy;

    IpCardSpreadEvaluator(RuleParameters parameters, VelocityRepository velocity, EventPrivacy privacy) {
        this.parameters = parameters;
        this.velocity = velocity;
        this.privacy = privacy;
    }

    @Override
    public RuleType type() {
        return RuleType.IP_CARD_SPREAD_VELOCITY;
    }

    @Override
    public Class<?> parametersType() {
        return Parameters.class;
    }

    @Override
    public RuleOutcome evaluate(Rule rule, TransactionEvent event) {
        Parameters params = parameters.read(rule, Parameters.class);

        // Card-present transactions carry no IP. Not evaluable rather than not matched: the rule
        // did not run, which is not the same as running and finding nothing.
        if (!event.hasIpAddress()) {
            return RuleOutcome.notEvaluable(rule, "No IP address on this transaction, so card spread could not be computed");
        }

        Instant windowStart = event.occurredAt().minus(Duration.ofMinutes(params.windowMinutes()));

        // The column holds fingerprints, so the address is fingerprinted before the lookup.
        // Querying the raw address matches nothing and returns zero without erroring.
        long count = velocity.countDistinctCardsForIp(privacy.fingerprint(event.ipAddress()), windowStart, event.occurredAt());

        String observation = "%d cards on this IP in %d minutes (including this one), threshold %d"
                .formatted(count, params.windowMinutes(), params.maxCount());

        return count > params.maxCount()
                ? RuleOutcome.matched(rule, observation)
                : RuleOutcome.notMatched(rule, observation);
    }
    
}
