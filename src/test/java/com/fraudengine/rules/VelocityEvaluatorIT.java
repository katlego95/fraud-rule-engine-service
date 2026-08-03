package com.fraudengine.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.fraudengine.PostgresIntegrationTest;
import com.fraudengine.decision.DecisionService;
import com.fraudengine.domain.CardToken;
import com.fraudengine.domain.Channel;
import com.fraudengine.domain.Decision;
import com.fraudengine.domain.OutcomeStatus;
import com.fraudengine.domain.RuleOutcome;
import com.fraudengine.domain.TransactionEvent;
import com.fraudengine.domain.Verdict;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Velocity is windowed on event time, so these sequences are fixtures rather than timing: the
 * clock never advances during the test and every assertion is deterministic.
 */
@Transactional
class VelocityEvaluatorIT extends PostgresIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-08-03T09:00:00Z");

    @Autowired
    private DecisionService service;

    @Test
    void fourTransactionsInFiveMinutesIsWithinThreshold() {
        Decision last = null;
        for (int i = 0; i < 4; i++) {
            last = decide(card("card-a"), T0.plus(Duration.ofSeconds(30L * i)), "m1").decision();
        }

        assertThat(outcome(last, "CARD_TXN_VELOCITY").status()).isEqualTo(OutcomeStatus.NOT_MATCHED);
        assertThat(last.verdict()).isEqualTo(Verdict.APPROVE);
    }

    @Test
    void fifthTransactionInFiveMinutesBlocks() {
        Decision last = null;
        for (int i = 0; i < 5; i++) {
            last = decide(card("card-b"), T0.plus(Duration.ofSeconds(30L * i)), "m1").decision();
        }

        RuleOutcome velocity = outcome(last, "CARD_TXN_VELOCITY");
        assertThat(velocity.status()).isEqualTo(OutcomeStatus.MATCHED);
        assertThat(velocity.reason()).contains("5 transactions").contains("including this one");
        assertThat(last.verdict()).isEqualTo(Verdict.BLOCK);
    }

    @Test
    void transactionsOutsideTheWindowDoNotCount() {
        CardToken card = card("card-c");
        for (int i = 0; i < 4; i++) {
            decide(card, T0.plus(Duration.ofSeconds(30L * i)), "m1");
        }
        // Six minutes after the first, so only this one and none of the earlier four are in window.
        Decision later = decide(card, T0.plus(Duration.ofMinutes(20)), "m1").decision();

        assertThat(outcome(later, "CARD_TXN_VELOCITY").status()).isEqualTo(OutcomeStatus.NOT_MATCHED);
        assertThat(outcome(later, "CARD_TXN_VELOCITY").reason()).contains("1 transactions");
    }

    @Test
    void windowBoundaryIsInclusiveAtBothEnds() {
        CardToken card = card("card-d");
        // Exactly five minutes apart: the earlier transaction sits on the window's lower edge.
        decide(card, T0, "m1");
        Decision second = decide(card, T0.plus(Duration.ofMinutes(5)), "m1").decision();

        assertThat(outcome(second, "CARD_TXN_VELOCITY").reason()).contains("2 transactions");
    }

    @Test
    void fourDistinctMerchantsInTenMinutesContributesToScore() {
        CardToken card = card("card-e");
        Decision last = null;
        for (int i = 0; i < 4; i++) {
            last = decide(card, T0.plus(Duration.ofMinutes(i)), "merchant-" + i).decision();
        }

        RuleOutcome spread = outcome(last, "MERCHANT_SPREAD_VELOCITY");
        assertThat(spread.status()).isEqualTo(OutcomeStatus.MATCHED);
        assertThat(spread.contribution()).isEqualTo(20);
        assertThat(spread.reason()).contains("4 distinct merchants");
    }

    @Test
    void repeatedUseOfOneMerchantIsNotSpread() {
        CardToken card = card("card-f");
        Decision last = null;
        for (int i = 0; i < 4; i++) {
            last = decide(card, T0.plus(Duration.ofMinutes(i)), "same-merchant").decision();
        }

        assertThat(outcome(last, "MERCHANT_SPREAD_VELOCITY").status())
                .isEqualTo(OutcomeStatus.NOT_MATCHED);
    }

    @Test
    void accountSpendAboveFiftyThousandInTwentyFourHoursIsReviewed() {
        String account = "acct-drain";
        Decision last = null;
        for (int i = 0; i < 3; i++) {
            last = decideAmount(account, card("card-g"), T0.plus(Duration.ofHours(i)),
                    new BigDecimal("20000.00")).decision();
        }

        RuleOutcome velocity = outcome(last, "ACCOUNT_AMOUNT_VELOCITY");
        assertThat(velocity.status()).isEqualTo(OutcomeStatus.MATCHED);
        assertThat(velocity.reason()).contains("R60000.00").contains("including this transaction");
        // Decisive REVIEW; the score reaches only 15 (high amount), so the decisive half wins.
        assertThat(last.decisiveVerdict()).isEqualTo(Verdict.REVIEW);
        assertThat(last.scoreVerdict()).isEqualTo(Verdict.APPROVE);
        assertThat(last.verdict()).isEqualTo(Verdict.REVIEW);
    }

    @Test
    void accountSpendIsCountedPerAccountNotPerCard() {
        // Same account, different cards: the window must still aggregate.
        decideAmount("acct-shared", card("card-h1"), T0, new BigDecimal("30000.00"));
        Decision second = decideAmount("acct-shared", card("card-h2"), T0.plus(Duration.ofMinutes(1)),
                new BigDecimal("30000.00")).decision();

        assertThat(outcome(second, "ACCOUNT_AMOUNT_VELOCITY").status()).isEqualTo(OutcomeStatus.MATCHED);
    }

    private com.fraudengine.decision.DecisionResult decide(CardToken card, Instant at, String merchant) {
        return decideAmount("acct-" + card.value(), card, at, new BigDecimal("100.00"), merchant);
    }

    private com.fraudengine.decision.DecisionResult decideAmount(
            String account, CardToken card, Instant at, BigDecimal amount) {
        return decideAmount(account, card, at, amount, "m1");
    }

    private com.fraudengine.decision.DecisionResult decideAmount(
            String account, CardToken card, Instant at, BigDecimal amount, String merchant) {
        return service.decide(new TransactionEvent(UUID.randomUUID(), at, account, card, amount,
                "ZAR", merchant, "Merchant", "5411", "ZA", Channel.CARD_PRESENT,
                null, null, null, null, "groceries"));
    }

    private static CardToken card(String value) {
        return new CardToken(value);
    }

    private static RuleOutcome outcome(Decision decision, String code) {
        return decision.outcomes().stream()
                .filter(o -> o.ruleCode().equals(code))
                .findFirst()
                .orElseThrow();
    }
}
