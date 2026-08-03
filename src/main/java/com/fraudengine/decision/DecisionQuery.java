package com.fraudengine.decision;

import com.fraudengine.domain.Verdict;
import java.time.Instant;
import java.util.List;

/**
 * Filters for the decision listing, combined with AND. {@code from} and {@code to} bound
 * {@code evaluated_at} — the time the decision was made, which is what this listing is ordered by.
 */
public record DecisionQuery(
        String accountId,
        String cardToken,
        List<Verdict> verdicts,
        String ruleCode,
        Integer minScore,
        Instant from,
        Instant to,
        Cursor cursor) {

    public DecisionQuery {
        verdicts = verdicts == null ? List.of() : List.copyOf(verdicts);
    }
}
