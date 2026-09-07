package com.fraudengine.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.fraudengine.PostgresIntegrationTest;
import com.fraudengine.decision.DecisionResult;
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
 * Spread rules count distinct identifiers seen from one source, rather than activity on one card.
 *
 * <p>Both read columns that are stored hashed, so these are also the only tests that prove the
 * evaluator fingerprints the value before querying: a lookup by the raw address matches nothing
 * and returns zero, which reads as a clean transaction and fails nothing.
 *
 * <p>Windows are on event time and the clock never advances here, so every sequence is a fixture
 * and every assertion deterministic.
 */
@Transactional
class SpreadEvaluatorIT extends PostgresIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-08-03T09:00:00Z");

    private static final String IP = "196.25.1.4";
    private static final String DEVICE = "device-abc";

    @Autowired
    private DecisionService service;

    // IP_CARD_SPREAD: more than 5 distinct cards from one IP within 10 minutes.

    /** At the threshold, not over it: the rule is "more than 5". */
    @Test
    void fiveCardsFromOneIpIsWithinThreshold() {
        Decision last = null;
        for (int i = 0; i < 5; i++) {
            last = decide("acct-" + i, card("card-a" + i), T0.plusSeconds(30L * i), null, IP)
                    .decision();
        }

        RuleOutcome spread = outcome(last, "IP_CARD_SPREAD");
        assertThat(spread.status()).isEqualTo(OutcomeStatus.NOT_MATCHED);
        assertThat(spread.reason()).contains("5 cards").contains("threshold 5");
    }

    /** Regression test for the raw-address lookup: unfingerprinted, the count is zero and this passes as clean. */
    @Test
    void sixCardsFromOneIpMatches() {
        Decision last = null;
        for (int i = 0; i < 6; i++) {
            last = decide("acct-" + i, card("card-b" + i), T0.plusSeconds(30L * i), null, IP)
                    .decision();
        }

        RuleOutcome spread = outcome(last, "IP_CARD_SPREAD");
        assertThat(spread.status()).isEqualTo(OutcomeStatus.MATCHED);
        assertThat(spread.reason()).contains("6 cards").contains("including this one");
    }

    /** A cardholder retrying a declined payment. count(*) would fire here; count(distinct) must not. */
    @Test
    void repeatedUseOfOneCardIsNotSpread() {
        CardToken card = card("card-c");
        Decision last = null;
        for (int i = 0; i < 6; i++) {
            last = decide("acct-c", card, T0.plusSeconds(30L * i), null, IP).decision();
        }

        assertThat(outcome(last, "IP_CARD_SPREAD").status()).isEqualTo(OutcomeStatus.NOT_MATCHED);
    }

    /** Six cards, six addresses: the count is per IP, so no single source is spread. */
    @Test
    void cardsFromDifferentIpsDoNotAggregate() {
        Decision last = null;
        for (int i = 0; i < 6; i++) {
            last = decide("acct-" + i, card("card-d" + i), T0.plusSeconds(30L * i),
                    null, "196.25.1." + i).decision();
        }

        assertThat(outcome(last, "IP_CARD_SPREAD").status()).isEqualTo(OutcomeStatus.NOT_MATCHED);
    }

    /** The window slides with event time, so yesterday's burst cannot condemn today's transaction. */
    @Test
    void cardsOutsideTheWindowDoNotCount() {
        for (int i = 0; i < 6; i++) {
            decide("acct-" + i, card("card-e" + i), T0.plusSeconds(30L * i), null, IP);
        }
        // Half an hour on, so the six earlier transactions have all left the ten-minute window.
        Decision later = decide("acct-late", card("card-e-late"), T0.plus(Duration.ofMinutes(30)),
                null, IP).decision();

        RuleOutcome spread = outcome(later, "IP_CARD_SPREAD");
        assertThat(spread.status()).isEqualTo(OutcomeStatus.NOT_MATCHED);
        assertThat(spread.reason()).contains("1 cards");
    }

    /** A missing input means the rule could not run — never that it ran and found nothing. */
    @Test
    void transactionWithoutAnIpIsNotEvaluableRatherThanClean() {
        Decision decision = decide("acct-noip", card("card-f"), T0, DEVICE, null).decision();

        RuleOutcome spread = outcome(decision, "IP_CARD_SPREAD");
        assertThat(spread.status()).isEqualTo(OutcomeStatus.NOT_EVALUABLE);
        assertThat(spread.reason()).contains("No IP address");
    }

    // DEVICE_ACCOUNT_SPREAD: more than 3 distinct accounts from one device within 24 hours.

    /** A household device across three accounts is ordinary. */
    @Test
    void threeAccountsFromOneDeviceIsWithinThreshold() {
        Decision last = null;
        for (int i = 0; i < 3; i++) {
            last = decide("acct-g" + i, card("card-g" + i), T0.plus(Duration.ofHours(i)),
                    DEVICE, null).decision();
        }

        RuleOutcome spread = outcome(last, "DEVICE_ACCOUNT_SPREAD");
        assertThat(spread.status()).isEqualTo(OutcomeStatus.NOT_MATCHED);
        assertThat(spread.reason()).contains("3 accounts").contains("threshold 3");
    }

    /** The fourth account is what separates a shared device from a mule operator's. */
    @Test
    void fourAccountsFromOneDeviceMatches() {
        Decision last = null;
        for (int i = 0; i < 4; i++) {
            last = decide("acct-h" + i, card("card-h" + i), T0.plus(Duration.ofHours(i)),
                    DEVICE, null).decision();
        }

        RuleOutcome spread = outcome(last, "DEVICE_ACCOUNT_SPREAD");
        assertThat(spread.status()).isEqualTo(OutcomeStatus.MATCHED);
        assertThat(spread.reason()).contains("4 accounts").contains("including this one");
    }

    /** One person, one device, one account, several purchases. The ordinary case must stay quiet. */
    @Test
    void repeatedUseOfOneAccountIsNotSpread() {
        Decision last = null;
        for (int i = 0; i < 6; i++) {
            last = decide("acct-i", card("card-i"), T0.plus(Duration.ofHours(i)), DEVICE, null)
                    .decision();
        }

        assertThat(outcome(last, "DEVICE_ACCOUNT_SPREAD").status())
                .isEqualTo(OutcomeStatus.NOT_MATCHED);
    }

    /** Four accounts, four devices: without a shared device there is nothing to link them. */
    @Test
    void accountsFromDifferentDevicesDoNotAggregate() {
        Decision last = null;
        for (int i = 0; i < 4; i++) {
            last = decide("acct-j" + i, card("card-j" + i), T0.plus(Duration.ofHours(i)),
                    "device-" + i, null).decision();
        }

        assertThat(outcome(last, "DEVICE_ACCOUNT_SPREAD").status())
                .isEqualTo(OutcomeStatus.NOT_MATCHED);
    }

    /** Same distinction as the IP case, on the other identifier. */
    @Test
    void transactionWithoutADeviceIsNotEvaluableRatherThanClean() {
        Decision decision = decide("acct-nodev", card("card-k"), T0, null, IP).decision();

        RuleOutcome spread = outcome(decision, "DEVICE_ACCOUNT_SPREAD");
        assertThat(spread.status()).isEqualTo(OutcomeStatus.NOT_EVALUABLE);
        assertThat(spread.reason()).contains("No device ID");
    }

    /** Shadow mode: the weight it would have contributed is recorded, then excluded from the verdict. */
    @Test
    void matchedShadowRuleRecordsItsWeightButChangesNothing() {
        Decision last = null;
        for (int i = 0; i < 6; i++) {
            last = decide("acct-l" + i, card("card-l" + i), T0.plusSeconds(30L * i), DEVICE, IP)
                    .decision();
        }

        RuleOutcome spread = outcome(last, "IP_CARD_SPREAD");
        assertThat(spread.status()).isEqualTo(OutcomeStatus.MATCHED);
        assertThat(spread.contribution()).isEqualTo(20);
        assertThat(spread.countsTowardsVerdict()).isFalse();

        // The weight is recorded on the outcome and absent from the score it would have moved.
        assertThat(last.totalScore()).isZero();
        assertThat(last.verdict()).isEqualTo(Verdict.APPROVE);
    }

    private DecisionResult decide(String account, CardToken card, Instant at,
            String deviceId, String ipAddress) {
        return service.decide(new TransactionEvent(UUID.randomUUID(), at, account, card,
                new BigDecimal("100.00"), "ZAR", "m1", "Merchant", "5411", "ZA", Channel.ECOMMERCE,
                null, null, deviceId, ipAddress, "groceries"));
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
