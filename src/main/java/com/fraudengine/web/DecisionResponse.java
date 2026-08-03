package com.fraudengine.web;

import com.fraudengine.domain.CardToken;
import com.fraudengine.domain.Decision;
import com.fraudengine.domain.RuleOutcome;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit view. Carries enough to reconstruct the decision without consulting the rules table,
 * because the rules table may have changed since the decision was made.
 *
 * @param rules every rule that was evaluated, not only those that matched — a rule that ran and
 *     did not match and a rule that could not run are different facts, and both are recorded
 * @param replayed true when an earlier submission of this event identifier produced this decision
 */
public record DecisionResponse(
        UUID decisionId,
        UUID eventId,
        String accountId,
        CardToken cardToken,
        Instant occurredAt,
        Instant evaluatedAt,
        String verdict,
        String decisiveVerdict,
        String scoreVerdict,
        int totalScore,
        Bands bands,
        String engineVersion,
        boolean replayed,
        JsonNode event,
        List<RuleOutcomeResponse> rules) {

    public record Bands(int reviewFrom, int blockFrom) {}

    public record RuleOutcomeResponse(
            UUID ruleId,
            String ruleCode,
            int ruleVersion,
            String mode,
            String nature,
            String status,
            String verdict,
            int contribution,
            String reason) {

        static RuleOutcomeResponse from(RuleOutcome outcome) {
            return new RuleOutcomeResponse(outcome.ruleId(), outcome.ruleCode(), outcome.ruleVersion(),
                    outcome.mode().name(), outcome.nature().name(), outcome.status().name(),
                    outcome.verdict() == null ? null : outcome.verdict().name(),
                    outcome.contribution(), outcome.reason());
        }
    }

    static DecisionResponse from(Decision decision, boolean replayed, JsonMapper json) {
        return new DecisionResponse(
                decision.decisionId(),
                decision.eventId(),
                decision.accountId(),
                decision.cardToken(),
                decision.occurredAt(),
                decision.evaluatedAt(),
                decision.verdict().name(),
                decision.decisiveVerdict().name(),
                decision.scoreVerdict().name(),
                decision.totalScore(),
                new Bands(decision.reviewFrom(), decision.blockFrom()),
                decision.engineVersion(),
                replayed,
                json.readTree(decision.eventSnapshot()),
                decision.outcomes().stream().map(RuleOutcomeResponse::from).toList());
    }
}
