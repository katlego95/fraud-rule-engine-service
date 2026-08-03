package com.fraudengine.generator;

import com.fraudengine.domain.TransactionEvent;
import java.util.List;

/**
 * An injected fraud sequence and the label that makes it ground truth.
 *
 * <p>The calibration report measures the engine against {@code expectedRuleCode}, so this label is
 * not documentation — it is the assertion. A scenario whose events do not actually exhibit the
 * typology it claims turns the calibration table into a fiction, which is why each scenario is
 * constructed to trip exactly one intended rule and the test asserts that it does.
 *
 * @param typology the named fraud pattern, for the report and the README
 * @param expectedRuleCode the rule that must catch this scenario for the rule set to be doing its job
 */
public record LabelledScenario(
        String name,
        String typology,
        String expectedRuleCode,
        String description,
        List<TransactionEvent> events) {

    /** The transaction the rule is expected to fire on — the last in the sequence. */
    public TransactionEvent triggeringEvent() {
        return events.getLast();
    }

    /**
     * The same sequence with fresh identifiers, shifted so it ends at {@code origin}, preserving
     * the intervals between events.
     *
     * <p>The seeded corpus uses fixed identifiers so a restart is idempotent. The demo endpoint
     * needs the opposite: firing a scenario twice should produce two decisions, not a replay of
     * the first. Rebasing gives that without abandoning event time — the intervals that make the
     * velocity windows fire are exactly what is preserved.
     */
    public LabelledScenario rebasedTo(java.time.Instant origin) {
        java.time.Instant last = triggeringEvent().occurredAt();
        List<TransactionEvent> shifted = events.stream()
                .map(event -> withIdentityAndTime(event, java.util.UUID.randomUUID(),
                        origin.minus(java.time.Duration.between(event.occurredAt(), last))))
                .toList();
        return new LabelledScenario(name, typology, expectedRuleCode, description, shifted);
    }

    private static TransactionEvent withIdentityAndTime(
            TransactionEvent event, java.util.UUID eventId, java.time.Instant occurredAt) {
        return new TransactionEvent(eventId, occurredAt, event.accountId(), event.cardToken(),
                event.amount(), event.currency(), event.merchantId(), event.merchantName(),
                event.merchantCategoryCode(), event.merchantCountry(), event.channel(),
                event.latitude(), event.longitude(), event.deviceId(), event.ipAddress(),
                event.category());
    }
}
