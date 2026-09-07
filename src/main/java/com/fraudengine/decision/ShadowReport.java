package com.fraudengine.decision;

import java.time.Instant;

/**
 * What a rule running in shadow would have done, over a window of decisions already made.
 *
 * <p>{@code matched} is the cheap number and the misleading one: a rule can match constantly and
 * still change nothing, because a contributory weight only matters where it crosses a band.
 * {@code wouldHaveChangedVerdict} is the number a promotion decision rests on.
 *
 * @param notEvaluable decisions where the rule could not run for want of an input. High counts here
 *     mean the rule is not being tested, whatever the match rate says.
 */
public record ShadowReport(
        String ruleCode,
        Instant from,
        Instant to,
        long decisionsEvaluated,
        long matched,
        long notMatched,
        long notEvaluable,
        long wouldHaveChangedVerdict,
        long wouldHaveMovedToReview,
        long wouldHaveMovedToBlock) {

    /** Of the decisions where the rule could run, the share it matched. */
    public double matchRatePercent() {
        long ran = matched + notMatched;
        return ran == 0 ? 0 : 100.0 * matched / ran;
    }

    /** Of the decisions it matched, the share where the match would have mattered. */
    public double effectiveRatePercent() {
        return matched == 0 ? 0 : 100.0 * wouldHaveChangedVerdict / matched;
    }
}
