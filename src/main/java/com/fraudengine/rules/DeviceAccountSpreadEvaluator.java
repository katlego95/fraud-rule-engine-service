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
 * Mule networks and account takeover: one device transacting across many accounts. A genuinely
 * shared device — a family tablet — touches few, so the spread of accounts is the signal.
 */
@Component
class DeviceAccountSpreadEvaluator implements RuleEvaluator {

    /**
     * Hours, not minutes. A device moving between accounts is a pattern that plays out over a
     * working day or longer, unlike card testing, which is a burst.
     */
    record Parameters(int maxCount, int windowHours) {}

    private final RuleParameters parameters;
    private final VelocityRepository velocity;
    private final EventPrivacy privacy;

    DeviceAccountSpreadEvaluator(RuleParameters parameters, VelocityRepository velocity, EventPrivacy privacy) {
        this.parameters = parameters;
        this.velocity = velocity;
        this.privacy = privacy;
    }

    @Override
    public RuleType type() {
        return RuleType.DEVICE_ACCOUNT_SPREAD_VELOCITY;
    }

    @Override
    public RuleOutcome evaluate(Rule rule, TransactionEvent event) {
        Parameters params = parameters.read(rule, Parameters.class);

        // Not every channel reports a device. Not evaluable rather than not matched: the rule did
        // not run, which is not the same as running and finding nothing.
        if (!event.hasDeviceId()) {
            return RuleOutcome.notEvaluable(rule, "No device ID on this transaction, so device spread could not be computed");
        }

        Instant windowStart = event.occurredAt().minus(Duration.ofHours(params.windowHours()));

        // The column holds fingerprints, so the identifier is fingerprinted before the lookup.
        // Querying the raw identifier matches nothing and returns zero without erroring.
        long count = velocity.countDistinctAccountsForDevice(privacy.fingerprint(event.deviceId()), windowStart, event.occurredAt());

        String observation = "%d accounts on this device in %d hours (including this one), threshold %d"
                .formatted(count, params.windowHours(), params.maxCount());

        return count > params.maxCount()
                ? RuleOutcome.matched(rule, observation)
                : RuleOutcome.notMatched(rule, observation);
    }
    
}
