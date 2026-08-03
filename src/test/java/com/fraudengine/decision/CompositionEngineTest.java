package com.fraudengine.decision;

import static com.fraudengine.domain.OutcomeStatus.MATCHED;
import static com.fraudengine.domain.OutcomeStatus.NOT_EVALUABLE;
import static com.fraudengine.domain.OutcomeStatus.NOT_MATCHED;
import static com.fraudengine.domain.RuleMode.ACTIVE;
import static com.fraudengine.domain.RuleMode.SHADOW;
import static com.fraudengine.domain.RuleNature.CONTRIBUTORY;
import static com.fraudengine.domain.RuleNature.DECISIVE;
import static com.fraudengine.domain.Verdict.APPROVE;
import static com.fraudengine.domain.Verdict.BLOCK;
import static com.fraudengine.domain.Verdict.REVIEW;
import static org.assertj.core.api.Assertions.assertThat;

import com.fraudengine.domain.OutcomeStatus;
import com.fraudengine.domain.RuleMode;
import com.fraudengine.domain.RuleNature;
import com.fraudengine.domain.RuleOutcome;
import com.fraudengine.domain.Verdict;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CompositionEngineTest {

    private static final ScoreBands BANDS = new ScoreBands(40, 70);

    private final CompositionEngine engine = new CompositionEngine(BANDS);

    @Test
    void noRulesApproves() {
        Composition composition = engine.compose(List.of());

        assertThat(composition.finalVerdict()).isEqualTo(APPROVE);
        assertThat(composition.decisiveVerdict()).isEqualTo(APPROVE);
        assertThat(composition.scoreVerdict()).isEqualTo(APPROVE);
        assertThat(composition.totalScore()).isZero();
    }

    @Test
    void rulesThatDidNotMatchContributeNothing() {
        Composition composition = engine.compose(List.of(
                contributory(ACTIVE, NOT_MATCHED, 25),
                decisive(ACTIVE, NOT_MATCHED, BLOCK)));

        assertThat(composition.finalVerdict()).isEqualTo(APPROVE);
        assertThat(composition.totalScore()).isZero();
    }

    @ParameterizedTest(name = "score {0} lands in {1}")
    @CsvSource({"0,APPROVE", "39,APPROVE", "40,REVIEW", "41,REVIEW", "69,REVIEW", "70,BLOCK", "200,BLOCK"})
    void scoreLandsInTheRightBandAtEveryBoundary(int score, Verdict expected) {
        Composition composition = engine.compose(List.of(contributory(ACTIVE, MATCHED, score)));

        assertThat(composition.totalScore()).isEqualTo(score);
        assertThat(composition.scoreVerdict()).isEqualTo(expected);
        assertThat(composition.finalVerdict()).isEqualTo(expected);
    }

    @Test
    void contributoryWeightsAccumulate() {
        Composition composition = engine.compose(List.of(
                contributory(ACTIVE, MATCHED, 15),
                contributory(ACTIVE, MATCHED, 15),
                contributory(ACTIVE, MATCHED, 25)));

        assertThat(composition.totalScore()).isEqualTo(55);
        assertThat(composition.finalVerdict()).isEqualTo(REVIEW);
    }

    @Test
    void mostSevereDecisiveVerdictWins() {
        Composition composition = engine.compose(List.of(
                decisive(ACTIVE, MATCHED, REVIEW),
                decisive(ACTIVE, MATCHED, BLOCK),
                decisive(ACTIVE, MATCHED, REVIEW)));

        assertThat(composition.decisiveVerdict()).isEqualTo(BLOCK);
        assertThat(composition.finalVerdict()).isEqualTo(BLOCK);
    }

    @Test
    void decisiveVerdictWinsOverALowerScoreBand() {
        Composition composition = engine.compose(List.of(
                decisive(ACTIVE, MATCHED, BLOCK),
                contributory(ACTIVE, MATCHED, 10)));

        assertThat(composition.scoreVerdict()).isEqualTo(APPROVE);
        assertThat(composition.finalVerdict()).isEqualTo(BLOCK);
    }

    @Test
    void scoreBandWinsOverALowerDecisiveVerdict() {
        Composition composition = engine.compose(List.of(
                decisive(ACTIVE, MATCHED, REVIEW),
                contributory(ACTIVE, MATCHED, 70)));

        assertThat(composition.decisiveVerdict()).isEqualTo(REVIEW);
        assertThat(composition.scoreVerdict()).isEqualTo(BLOCK);
        assertThat(composition.finalVerdict()).isEqualTo(BLOCK);
    }

    @Test
    void shadowRulesAreExcludedFromBothHalves() {
        Composition composition = engine.compose(List.of(
                decisive(SHADOW, MATCHED, BLOCK),
                contributory(SHADOW, MATCHED, 90)));

        assertThat(composition.finalVerdict()).isEqualTo(APPROVE);
        assertThat(composition.totalScore()).isZero();
    }

    @Test
    void shadowOutcomesStillRecordWhatTheyWouldHaveContributed() {
        RuleOutcome shadow = contributory(SHADOW, MATCHED, 90);

        assertThat(engine.compose(List.of(shadow)).totalScore()).isZero();
        assertThat(shadow.contribution()).isEqualTo(90);
    }

    @Test
    void unevaluableRulesAffectNeitherHalf() {
        Composition composition = engine.compose(List.of(
                decisive(ACTIVE, NOT_EVALUABLE, BLOCK),
                contributory(ACTIVE, NOT_EVALUABLE, 90)));

        assertThat(composition.finalVerdict()).isEqualTo(APPROVE);
        assertThat(composition.totalScore()).isZero();
    }

    @Test
    void activeAndShadowRulesMixWithoutTheShadowLeaking() {
        Composition composition = engine.compose(List.of(
                contributory(ACTIVE, MATCHED, 40),
                contributory(SHADOW, MATCHED, 40),
                decisive(SHADOW, MATCHED, BLOCK),
                decisive(ACTIVE, NOT_MATCHED, BLOCK)));

        assertThat(composition.totalScore()).isEqualTo(40);
        assertThat(composition.finalVerdict()).isEqualTo(REVIEW);
    }

    @Test
    void bandsUsedAreCarriedOnTheComposition() {
        assertThat(engine.compose(List.of()).bands()).isEqualTo(BANDS);
    }

    private static RuleOutcome decisive(RuleMode mode, OutcomeStatus status, Verdict verdict) {
        return outcome(mode, DECISIVE, status, verdict, 0);
    }

    private static RuleOutcome contributory(RuleMode mode, OutcomeStatus status, int contribution) {
        return outcome(mode, CONTRIBUTORY, status, null, contribution);
    }

    private static RuleOutcome outcome(RuleMode mode, RuleNature nature, OutcomeStatus status,
            Verdict verdict, int contribution) {
        return new RuleOutcome(UUID.randomUUID(), "RULE", 1, mode, nature, status, verdict,
                contribution, "test outcome");
    }
}
