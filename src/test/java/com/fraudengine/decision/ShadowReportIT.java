package com.fraudengine.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.fraudengine.PostgresIntegrationTest;
import com.fraudengine.domain.CardToken;
import com.fraudengine.domain.Channel;
import com.fraudengine.domain.Decision;
import com.fraudengine.domain.RuleMode;
import com.fraudengine.domain.TransactionEvent;
import com.fraudengine.domain.Verdict;
import com.fraudengine.rules.RuleRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading back what a shadow rule would have done.
 *
 * <p>The window is on evaluated_at, which is the server clock rather than event time, so these
 * tests bound it generously instead of pinning it: what is being asserted is the arithmetic over
 * outcomes, not the clock.
 */
@Transactional
class ShadowReportIT extends PostgresIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-08-03T09:00:00Z");

    @Autowired
    private DecisionService decisions;

    @Autowired
    private RuleRepository rules;

    @Autowired
    private ShadowReportRepository reports;

    @Test
    void aShadowRuleThatNeverCrossesABandChangesNothing() {
        shadow("HIGH_AMOUNT");

        // R12,000 trips HIGH_AMOUNT for 15, which alone does not reach the review band of 40.
        decide(new BigDecimal("12000.00"), 0);

        ShadowReport report = report("HIGH_AMOUNT");
        assertThat(report.matched()).isEqualTo(1);
        assertThat(report.wouldHaveChangedVerdict()).isZero();
    }

    /** The number a promotion decision rests on: matches that would have moved the verdict. */
    @Test
    void aShadowRuleThatCrossesABandIsCountedAsAChange() {
        shadow("CNP_HIGH_AMOUNT");

        // Online, R12,000: HIGH_AMOUNT scores 15 for real, and the shadowed CNP rule would have
        // added 25 — exactly the review band.
        Decision decision = decideOnline(new BigDecimal("12000.00"), 0);
        assertThat(decision.verdict()).isEqualTo(Verdict.APPROVE);
        assertThat(decision.totalScore()).isEqualTo(15);

        ShadowReport report = report("CNP_HIGH_AMOUNT");
        assertThat(report.matched()).isEqualTo(1);
        assertThat(report.wouldHaveChangedVerdict()).isEqualTo(1);
        assertThat(report.wouldHaveMovedToReview()).isEqualTo(1);
        assertThat(report.wouldHaveMovedToBlock()).isZero();
    }

    @Test
    void aRuleThatDidNotMatchIsCountedButChangesNothing() {
        shadow("CNP_HIGH_AMOUNT");

        decide(new BigDecimal("120.00"), 0);

        ShadowReport report = report("CNP_HIGH_AMOUNT");
        assertThat(report.notMatched()).isEqualTo(1);
        assertThat(report.matched()).isZero();
        assertThat(report.wouldHaveChangedVerdict()).isZero();
    }

    /** A rule that could not run is not a rule that found nothing, and the report keeps them apart. */
    @Test
    void unevaluableOutcomesAreReportedSeparately() {
        shadow("GEO_IMPOSSIBLE");

        decide(new BigDecimal("120.00"), 0);

        ShadowReport report = report("GEO_IMPOSSIBLE");
        assertThat(report.notEvaluable()).isEqualTo(1);
        assertThat(report.matched()).isZero();
    }

    /**
     * The correctness that matters most. An active rule's weight is already inside total_score,
     * so counting it again would report verdict changes that could never have happened.
     */
    @Test
    void outcomesRecordedWhileTheRuleWasActiveAreExcluded() {
        decide(new BigDecimal("12000.00"), 0);

        assertThat(report("HIGH_AMOUNT").decisionsEvaluated()).isZero();
    }

    @Test
    void decisionsOutsideTheWindowAreExcluded() {
        shadow("HIGH_AMOUNT");
        decide(new BigDecimal("12000.00"), 0);

        ShadowReport past = reports.reportFor("HIGH_AMOUNT",
                Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2020-12-31T00:00:00Z"));

        assertThat(past.decisionsEvaluated()).isZero();
    }

    @Test
    void ratesAreDerivedFromTheCountsRatherThanStored() {
        shadow("CNP_HIGH_AMOUNT");

        decideOnline(new BigDecimal("12000.00"), 0);
        decide(new BigDecimal("120.00"), 1);

        ShadowReport report = report("CNP_HIGH_AMOUNT");
        assertThat(report.matched()).isEqualTo(1);
        assertThat(report.notMatched()).isEqualTo(1);
        assertThat(report.matchRatePercent()).isEqualTo(50.0);
        assertThat(report.effectiveRatePercent()).isEqualTo(100.0);
    }

    private void shadow(String code) {
        UUID id = rules.findCurrent().stream()
                .filter(rule -> rule.code().equals(code))
                .findFirst().orElseThrow().id();
        rules.changeMode(id, RuleMode.SHADOW);
    }

    private ShadowReport report(String code) {
        return reports.reportFor(code, Instant.EPOCH, Instant.parse("2099-01-01T00:00:00Z"));
    }

    private Decision decide(BigDecimal amount, int minutesOn) {
        return decide(amount, minutesOn, Channel.CARD_PRESENT);
    }

    private Decision decideOnline(BigDecimal amount, int minutesOn) {
        return decide(amount, minutesOn, Channel.ECOMMERCE);
    }

    private Decision decide(BigDecimal amount, int minutesOn, Channel channel) {
        return decisions.decide(new TransactionEvent(UUID.randomUUID(),
                T0.plus(Duration.ofMinutes(minutesOn)), "acct-shadow",
                new CardToken("4000111122223333"), amount, "ZAR", "m1", "Merchant", "5411", "ZA",
                channel, null, null, null, null, "retail")).decision();
    }
}
