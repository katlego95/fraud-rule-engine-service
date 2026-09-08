package com.fraudengine.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One version of one rule. Changing a rule inserts a new row with an incremented version and
 * supersedes the prior one, so a decision can pin the exact {@code id} that fired and stay
 * explainable after the rule has moved on.
 *
 * @param parameters raw JSON, deserialised and validated against this type by the evaluator that
 *     owns it — at evaluation time, not on write
 */
public record Rule(
        UUID id,
        String code,
        int version,
        RuleType type,
        RuleMode mode,
        RuleNature nature,
        Verdict verdict,
        Integer weight,
        String parameters,
        String description,
        String typology,
        Instant createdAt,
        Instant supersededAt) {

    /**
     * A rule as submitted, before it is stored. The identifier, version and timestamps are
     * assigned on insert, so they are absent here rather than invented by the caller.
     */
    public static Rule definition(String code, RuleType type, RuleMode mode, RuleNature nature,
            Verdict verdict, Integer weight, String parameters, String description,
            String typology) {
        return new Rule(null, code, 0, type, mode, nature, verdict, weight, parameters,
                description, typology, null, null);
    }

    public boolean isDecisive() {
        return nature == RuleNature.DECISIVE;
    }
}
