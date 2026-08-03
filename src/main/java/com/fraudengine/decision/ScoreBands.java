package com.fraudengine.decision;

import com.fraudengine.domain.Verdict;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The score-to-verdict bands. Configuration rather than constants, because thresholds are tuned
 * as fraudsters adapt and a threshold change should not be a code change.
 *
 * <p>Bands are half-open and integral: {@code score < reviewFrom} approves, {@code reviewFrom <=
 * score < blockFrom} reviews, {@code score >= blockFrom} blocks. Integral because a fractional
 * score against integral bands leaves gaps that only surface at a boundary.
 */
@ConfigurationProperties("fraud.score-bands")
public record ScoreBands(int reviewFrom, int blockFrom) {

    public ScoreBands {
        if (reviewFrom >= blockFrom) {
            throw new IllegalArgumentException(
                    "reviewFrom (%d) must be below blockFrom (%d)".formatted(reviewFrom, blockFrom));
        }
    }

    public Verdict verdictFor(int score) {
        if (score >= blockFrom) {
            return Verdict.BLOCK;
        }
        if (score >= reviewFrom) {
            return Verdict.REVIEW;
        }
        return Verdict.APPROVE;
    }
}
