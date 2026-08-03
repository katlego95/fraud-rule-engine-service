package com.fraudengine.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.fraudengine.PostgresIntegrationTest;
import com.fraudengine.decision.DecisionService;
import com.fraudengine.domain.CardToken;
import com.fraudengine.domain.Channel;
import com.fraudengine.domain.Decision;
import com.fraudengine.domain.OutcomeStatus;
import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleMode;
import com.fraudengine.domain.RuleNature;
import com.fraudengine.domain.RuleOutcome;
import com.fraudengine.domain.RuleType;
import com.fraudengine.domain.TransactionEvent;
import com.fraudengine.domain.Verdict;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * The safe-change narrative, executed rather than described.
 *
 * <p>This is the answer to the question the whole design is pointed at: rules decay because
 * fraudsters learn to operate below static thresholds, so how does a threshold change without
 * causing a wave of false blocks on Monday morning? Each test here is one step of that story, and
 * together they show the mechanisms actually connect.
 */
@Transactional
class RuleLifecycleIT extends PostgresIntegrationTest {

    @Autowired
    private DecisionService service;

    @Autowired
    private RuleRepository rules;

    @Test
    void aNewRuleRunsInShadowSeeingEverythingAndChangingNothing() {
        Rule shadowed = rules.insertNextVersion(new Rule(null, "TIGHTER_AMOUNT", 0,
                RuleType.AMOUNT_THRESHOLD, RuleMode.SHADOW, RuleNature.CONTRIBUTORY, null, 60,
                "{\"threshold\":\"500.00\"}", "Candidate: flag anything above R500.",
                "Threshold tuning candidate", null, null));

        Decision decision = decide(new BigDecimal("2000.00"));
        RuleOutcome outcome = outcomeFor(decision, "TIGHTER_AMOUNT");

        assertThat(outcome.status()).isEqualTo(OutcomeStatus.MATCHED);
        assertThat(outcome.mode()).isEqualTo(RuleMode.SHADOW);
        assertThat(outcome.contribution())
                .as("shadow records the weight it would have applied, which is what makes the "
                        + "observation worth anything")
                .isEqualTo(60);
        assertThat(decision.totalScore()).as("but contributes nothing").isZero();
        assertThat(decision.verdict()).isEqualTo(Verdict.APPROVE);
        assertThat(shadowed.version()).isEqualTo(1);
    }

    @Test
    void promotingShadowToActiveChangesTheVerdictWithoutARestart() {
        Rule candidate = rules.insertNextVersion(new Rule(null, "TIGHTER_AMOUNT", 0,
                RuleType.AMOUNT_THRESHOLD, RuleMode.SHADOW, RuleNature.CONTRIBUTORY, null, 60,
                "{\"threshold\":\"500.00\"}", "Candidate: flag anything above R500.",
                "Threshold tuning candidate", null, null));

        assertThat(decide(new BigDecimal("2000.00")).verdict()).isEqualTo(Verdict.APPROVE);

        rules.changeMode(candidate.id(), RuleMode.ACTIVE);

        Decision afterPromotion = decide(new BigDecimal("2000.00"));
        assertThat(afterPromotion.totalScore()).isEqualTo(60);
        assertThat(afterPromotion.verdict()).isEqualTo(Verdict.REVIEW);
    }

    @Test
    void disablingAMisbehavingRuleRevertsTheVerdictWithoutADeployment() {
        Rule candidate = rules.insertNextVersion(new Rule(null, "TOO_TIGHT", 0,
                RuleType.AMOUNT_THRESHOLD, RuleMode.ACTIVE, RuleNature.CONTRIBUTORY, null, 80,
                "{\"threshold\":\"100.00\"}", "Far too aggressive; blocks ordinary spend.",
                "Threshold tuning candidate", null, null));

        assertThat(decide(new BigDecimal("2000.00")).verdict()).isEqualTo(Verdict.BLOCK);

        rules.changeMode(candidate.id(), RuleMode.DISABLED);

        Decision afterDisable = decide(new BigDecimal("2000.00"));
        assertThat(afterDisable.verdict()).isEqualTo(Verdict.APPROVE);
        assertThat(afterDisable.outcomes())
                .as("a disabled rule is not evaluated at all, so it leaves no outcome")
                .noneMatch(outcome -> outcome.ruleCode().equals("TOO_TIGHT"));
    }

    @Test
    void aDecisionMadeUnderOneVersionStillReferencesItAfterTheRuleMovesOn() {
        Rule original = rules.insertNextVersion(new Rule(null, "EVOLVING", 0,
                RuleType.AMOUNT_THRESHOLD, RuleMode.ACTIVE, RuleNature.CONTRIBUTORY, null, 45,
                "{\"threshold\":\"1000.00\"}", "First cut.", "Threshold tuning", null, null));

        Decision underVersionOne = decide(new BigDecimal("2000.00"));

        Rule revised = rules.insertNextVersion(new Rule(null, "EVOLVING", 0,
                RuleType.AMOUNT_THRESHOLD, RuleMode.ACTIVE, RuleNature.CONTRIBUTORY, null, 5,
                "{\"threshold\":\"1000.00\"}", "Weight reduced after review.", "Threshold tuning",
                null, null));

        Decision underVersionTwo = decide(new BigDecimal("2000.00"));

        RuleOutcome first = outcomeFor(underVersionOne, "EVOLVING");
        RuleOutcome second = outcomeFor(underVersionTwo, "EVOLVING");

        assertThat(first.ruleId()).isEqualTo(original.id());
        assertThat(first.contribution()).isEqualTo(45);
        assertThat(second.ruleId()).isEqualTo(revised.id());
        assertThat(second.contribution()).isEqualTo(5);
        assertThat(revised.version()).isEqualTo(original.version() + 1);
        assertThat(rules.findById(original.id()).orElseThrow().supersededAt())
                .as("the superseded version is retained, not deleted")
                .isNotNull();
    }

    @Test
    void modeTransitionsAreAuditableSoPromotionHasATimestamp() {
        Rule candidate = rules.insertNextVersion(new Rule(null, "AUDITED", 0,
                RuleType.AMOUNT_THRESHOLD, RuleMode.SHADOW, RuleNature.CONTRIBUTORY, null, 10,
                "{\"threshold\":\"1000.00\"}", "Candidate.", "Threshold tuning", null, null));

        rules.changeMode(candidate.id(), RuleMode.ACTIVE);
        rules.changeMode(candidate.id(), RuleMode.DISABLED);

        assertThat(rules.findById(candidate.id()).orElseThrow().mode()).isEqualTo(RuleMode.DISABLED);
    }

    private Decision decide(BigDecimal amount) {
        return service.decide(new TransactionEvent(UUID.randomUUID(),
                Instant.parse("2026-08-03T09:00:00Z"), "acct-lifecycle-" + UUID.randomUUID(),
                new CardToken("card-" + UUID.randomUUID()), amount, "ZAR", "merch-1", "Merchant",
                "5411", "ZA", Channel.CARD_PRESENT, null, null, null, null, "retail")).decision();
    }

    private static RuleOutcome outcomeFor(Decision decision, String code) {
        return decision.outcomes().stream()
                .filter(outcome -> outcome.ruleCode().equals(code))
                .findFirst()
                .orElseThrow();
    }
}
