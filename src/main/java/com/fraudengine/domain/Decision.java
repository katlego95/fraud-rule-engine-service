package com.fraudengine.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An immutable record of one evaluation. Never updated, only inserted.
 *
 * <p>Carries everything needed to explain itself without consulting the rules table, because the
 * rules table may have changed since: the input as evaluated, every rule that ran with the exact
 * version that ran, both halves of the composition, and the bands in force at the time.
 */
public record Decision(
        UUID decisionId,
        UUID eventId,
        String accountId,
        CardToken cardToken,
        Instant occurredAt,
        Instant evaluatedAt,
        Verdict verdict,
        Verdict decisiveVerdict,
        Verdict scoreVerdict,
        int totalScore,
        int reviewFrom,
        int blockFrom,
        String engineVersion,
        String eventSnapshot,
        List<RuleOutcome> outcomes) {}
