package com.fraudengine.domain;

import java.util.UUID;

/**
 * What one rule did to one transaction, recorded whether or not it mattered.
 *
 * <p>{@code contribution} is what the rule produced, not what the verdict used: a matched
 * contributory rule in shadow mode records its full weight here and is excluded during
 * composition. That is what makes "what would this rule have done?" answerable from the decision
 * record alone.
 *
 * @param reason human-readable and the most valuable field here — it is what a call centre agent
 *     reads and what an auditor asks for
 */
public record RuleOutcome(
        UUID ruleId,
        String ruleCode,
        int ruleVersion,
        RuleMode mode,
        RuleNature nature,
        OutcomeStatus status,
        Verdict verdict,
        int contribution,
        String reason) {

    public static RuleOutcome matched(Rule rule, String reason) {
        return new RuleOutcome(rule.id(), rule.code(), rule.version(), rule.mode(), rule.nature(),
                OutcomeStatus.MATCHED, rule.verdict(),
                rule.weight() == null ? 0 : rule.weight(), reason);
    }

    public static RuleOutcome notMatched(Rule rule, String reason) {
        return new RuleOutcome(rule.id(), rule.code(), rule.version(), rule.mode(), rule.nature(),
                OutcomeStatus.NOT_MATCHED, null, 0, reason);
    }

    /** The rule could not run — a required input was absent. Never the same as "did not match". */
    public static RuleOutcome notEvaluable(Rule rule, String reason) {
        return new RuleOutcome(rule.id(), rule.code(), rule.version(), rule.mode(), rule.nature(),
                OutcomeStatus.NOT_EVALUABLE, null, 0, reason);
    }

    public boolean countsTowardsVerdict() {
        return status == OutcomeStatus.MATCHED && mode.affectsVerdict();
    }
}
