package com.fraudengine.domain;

import static com.fraudengine.domain.Verdict.APPROVE;
import static com.fraudengine.domain.Verdict.BLOCK;
import static com.fraudengine.domain.Verdict.REVIEW;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Severity is the enum's declaration order — {@code mostSevere} is a max over ordinals, and an
 * ordinal is just a constant's position in the source. Reordering the constants would silently
 * change what the engine decides, and the only thing saying so is a comment.
 *
 * <p>Two composition tests would catch a reorder today, but as a side effect of testing
 * composition: the failure would name "decisive verdict wins over a lower score band" rather than
 * the ordering. These name the invariant.
 */
class VerdictTest {

    @Test
    void blockIsTheMostSevere() {
        assertThat(Verdict.mostSevere(APPROVE, BLOCK)).isEqualTo(BLOCK);
        assertThat(Verdict.mostSevere(BLOCK, APPROVE)).isEqualTo(BLOCK);
        assertThat(Verdict.mostSevere(REVIEW, BLOCK)).isEqualTo(BLOCK);
        assertThat(Verdict.mostSevere(BLOCK, REVIEW)).isEqualTo(BLOCK);
    }

    @Test
    void reviewOutranksApprove() {
        assertThat(Verdict.mostSevere(APPROVE, REVIEW)).isEqualTo(REVIEW);
        assertThat(Verdict.mostSevere(REVIEW, APPROVE)).isEqualTo(REVIEW);
    }

    /** Both halves of the composition agreeing is not a case that needs resolving. */
    @Test
    void twoEqualVerdictsGiveThatVerdict() {
        assertThat(Verdict.mostSevere(APPROVE, APPROVE)).isEqualTo(APPROVE);
        assertThat(Verdict.mostSevere(REVIEW, REVIEW)).isEqualTo(REVIEW);
        assertThat(Verdict.mostSevere(BLOCK, BLOCK)).isEqualTo(BLOCK);
    }

    /**
     * The ordinals themselves, so a reorder fails here first and with an obvious message. Nothing
     * persists an ordinal — `decisions.verdict` is text — so a reorder could not corrupt stored
     * rows, only every comparison made from then on.
     */
    @Test
    void declarationOrderIsAscendingSeverity() {
        assertThat(Verdict.values()).containsExactly(APPROVE, REVIEW, BLOCK);
    }
}
