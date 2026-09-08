package com.fraudengine.rules;

import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleOutcome;
import com.fraudengine.domain.RuleType;
import com.fraudengine.domain.TransactionEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * A cloned card used in a second location: two transactions implying a journey nobody could have
 * made.
 *
 * <p>Three guards, each removing a class of false positive rather than a single special case:
 *
 * <ul>
 *   <li>Only physically-present channels are paired. E-commerce coordinates come from an IP
 *       address, so pairing one against a card-present location flags a customer buying online
 *       while travelling — a false positive the rule should never generate.
 *   <li>Pairs closer than the minimum distance are ignored. Coordinate jitter between neighbouring
 *       merchants produces supersonic implied speeds over a few seconds, and this floor absorbs
 *       that, the same-merchant case and the identical-timestamp division by zero in one rule
 *       instead of three special cases.
 *   <li>The event being evaluated is excluded from its own candidate set, so it cannot pair with
 *       itself at zero distance and zero elapsed time.
 * </ul>
 */
@Component
class GeoSpeedEvaluator implements RuleEvaluator {

    record Parameters(double maxSpeedKmh, double minDistanceKm, int lookbackHours) {}

    private final RuleParameters parameters;
    private final VelocityRepository velocity;

    GeoSpeedEvaluator(RuleParameters parameters, VelocityRepository velocity) {
        this.parameters = parameters;
        this.velocity = velocity;
    }

    @Override
    public RuleType type() {
        return RuleType.GEO_SPEED;
    }

    @Override
    public Class<?> parametersType() {
        return Parameters.class;
    }

    @Override
    public RuleOutcome evaluate(Rule rule, TransactionEvent event) {
        Parameters params = parameters.read(rule, Parameters.class);

        if (!event.hasCoordinates()) {
            return RuleOutcome.notEvaluable(rule, "No coordinates on this transaction, so implied travel speed could not be computed");
        }
        if (!event.channel().impliesPhysicalPresence()) {
            return RuleOutcome.notMatched(rule,
                    "Channel %s does not place the cardholder anywhere, so travel speed does not apply"
                            .formatted(event.channel()));
        }

        Instant windowStart = event.occurredAt().minus(Duration.ofHours(params.lookbackHours()));
        List<VelocityRepository.Position> priors =
                velocity.positionsExcluding(event.cardToken(), windowStart, event.occurredAt(), event.eventId())
                        .stream()
                        .filter(position -> position.channel().impliesPhysicalPresence())
                        .toList();

        if (priors.isEmpty()) {
            return RuleOutcome.notEvaluable(rule,
                    "No prior card-present transaction with coordinates in the last %d hours to compare against"
                            .formatted(params.lookbackHours()));
        }

        VelocityRepository.Position worst = null;
        double worstSpeed = 0;
        for (VelocityRepository.Position prior : priors) {
            double distanceKm = Haversine.distanceKm(
                    prior.latitude(), prior.longitude(), event.latitude(), event.longitude());
            if (distanceKm < params.minDistanceKm()) {
                continue;
            }
            double hours = Duration.between(prior.occurredAt(), event.occurredAt()).toMillis() / 3_600_000.0;
            if (hours <= 0) {
                // Same instant over a real distance is not a speed, it is two places at once.
                return RuleOutcome.matched(rule, "%.0f km from a transaction at the same instant"
                        .formatted(distanceKm));
            }
            double speed = distanceKm / hours;
            if (speed > worstSpeed) {
                worstSpeed = speed;
                worst = prior;
            }
        }

        if (worst == null) {
            return RuleOutcome.notMatched(rule,
                    "No prior transaction further than %.0f km away, so no travel to assess"
                            .formatted(params.minDistanceKm()));
        }

        String observation = "Implied travel of %.0f km/h from the previous transaction, threshold %.0f km/h"
                .formatted(worstSpeed, params.maxSpeedKmh());

        return worstSpeed > params.maxSpeedKmh()
                ? RuleOutcome.matched(rule, observation)
                : RuleOutcome.notMatched(rule, observation);
    }
}
