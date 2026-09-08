package com.fraudengine.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.fraudengine.domain.CardToken;
import com.fraudengine.domain.Channel;
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
import tools.jackson.databind.json.JsonMapper;

class StatelessEvaluatorTest {

    private final RuleParameters parameters = new RuleParameters(JsonMapper.builder().build());

    @Test
    void amountAboveThresholdMatches() {
        RuleOutcome outcome = new AmountThresholdEvaluator(parameters)
                .evaluate(contributoryRule(RuleType.AMOUNT_THRESHOLD, "{\"threshold\":\"10000.00\"}"),
                        event(new BigDecimal("10000.01"), Channel.CARD_PRESENT, "5411", "ZA"));

        assertThat(outcome.status()).isEqualTo(OutcomeStatus.MATCHED);
        assertThat(outcome.contribution()).isEqualTo(15);
        assertThat(outcome.reason()).isEqualTo("Amount R10000.01 is above the R10000.00 threshold");
    }

    @Test
    void amountExactlyOnThresholdDoesNotMatch() {
        RuleOutcome outcome = new AmountThresholdEvaluator(parameters)
                .evaluate(contributoryRule(RuleType.AMOUNT_THRESHOLD, "{\"threshold\":\"10000.00\"}"),
                        event(new BigDecimal("10000.00"), Channel.CARD_PRESENT, "5411", "ZA"));

        assertThat(outcome.status()).isEqualTo(OutcomeStatus.NOT_MATCHED);
        assertThat(outcome.contribution()).isZero();
    }

    @Test
    void thresholdComparisonIgnoresTrailingZeroScale() {
        RuleOutcome outcome = new AmountThresholdEvaluator(parameters)
                .evaluate(contributoryRule(RuleType.AMOUNT_THRESHOLD, "{\"threshold\":\"10000\"}"),
                        event(new BigDecimal("10000.00"), Channel.CARD_PRESENT, "5411", "ZA"));

        assertThat(outcome.status()).isEqualTo(OutcomeStatus.NOT_MATCHED);
    }

    @Test
    void merchantCategoryInTheHighRiskSetMatches() {
        RuleOutcome outcome = new MccSetEvaluator(parameters)
                .evaluate(contributoryRule(RuleType.MCC_SET, "{\"codes\":[\"7995\",\"6051\"]}"),
                        event(new BigDecimal("100.00"), Channel.CARD_PRESENT, "7995", "ZA"));

        assertThat(outcome.status()).isEqualTo(OutcomeStatus.MATCHED);
        assertThat(outcome.reason()).contains("7995");
    }

    @Test
    void merchantCategoryOutsideTheSetDoesNotMatch() {
        RuleOutcome outcome = new MccSetEvaluator(parameters)
                .evaluate(contributoryRule(RuleType.MCC_SET, "{\"codes\":[\"7995\"]}"),
                        event(new BigDecimal("100.00"), Channel.CARD_PRESENT, "5411", "ZA"));

        assertThat(outcome.status()).isEqualTo(OutcomeStatus.NOT_MATCHED);
    }

    @Test
    void blockedCountryEmitsTheRuleVerdict() {
        RuleOutcome outcome = new CountryBlocklistEvaluator(parameters)
                .evaluate(decisiveRule(RuleType.COUNTRY_BLOCKLIST, "{\"countries\":[\"KP\",\"IR\"]}"),
                        event(new BigDecimal("100.00"), Channel.ECOMMERCE, "5411", "KP"));

        assertThat(outcome.status()).isEqualTo(OutcomeStatus.MATCHED);
        assertThat(outcome.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(outcome.contribution()).isZero();
    }

    @Test
    void permittedCountryDoesNotMatch() {
        RuleOutcome outcome = new CountryBlocklistEvaluator(parameters)
                .evaluate(decisiveRule(RuleType.COUNTRY_BLOCKLIST, "{\"countries\":[\"KP\"]}"),
                        event(new BigDecimal("100.00"), Channel.ECOMMERCE, "5411", "ZA"));

        assertThat(outcome.status()).isEqualTo(OutcomeStatus.NOT_MATCHED);
        assertThat(outcome.verdict()).isNull();
    }

    @Test
    void cardNotPresentAboveThresholdMatchesOnEveryConfiguredChannel() {
        Rule rule = contributoryRule(RuleType.CHANNEL_AMOUNT,
                "{\"channels\":[\"ECOMMERCE\",\"TRANSFER\"],\"threshold\":\"5000.00\"}");
        ChannelAmountEvaluator evaluator = new ChannelAmountEvaluator(parameters);

        assertThat(evaluator.evaluate(rule, event(new BigDecimal("5000.01"), Channel.ECOMMERCE, "5411", "ZA")).status())
                .isEqualTo(OutcomeStatus.MATCHED);
        // The channel that used to slip through: card-not-present by definition, and the rule is
        // named for that category.
        assertThat(evaluator.evaluate(rule, event(new BigDecimal("5000.01"), Channel.TRANSFER, "5411", "ZA")).status())
                .isEqualTo(OutcomeStatus.MATCHED);

        assertThat(evaluator.evaluate(rule, event(new BigDecimal("5000.01"), Channel.CARD_PRESENT, "5411", "ZA")).status())
                .isEqualTo(OutcomeStatus.NOT_MATCHED);
        assertThat(evaluator.evaluate(rule, event(new BigDecimal("5000.01"), Channel.ATM, "5411", "ZA")).status())
                .isEqualTo(OutcomeStatus.NOT_MATCHED);
        assertThat(evaluator.evaluate(rule, event(new BigDecimal("5000.00"), Channel.ECOMMERCE, "5411", "ZA")).status())
                .isEqualTo(OutcomeStatus.NOT_MATCHED);
    }

    /** A single-channel rule stays expressible — the set did not remove the narrow case. */
    @Test
    void aChannelAmountRuleCanStillTargetOneChannel() {
        Rule rule = contributoryRule(RuleType.CHANNEL_AMOUNT,
                "{\"channels\":[\"ATM\"],\"threshold\":\"3000.00\"}");
        ChannelAmountEvaluator evaluator = new ChannelAmountEvaluator(parameters);

        assertThat(evaluator.evaluate(rule, event(new BigDecimal("3000.01"), Channel.ATM, "5411", "ZA")).status())
                .isEqualTo(OutcomeStatus.MATCHED);
        assertThat(evaluator.evaluate(rule, event(new BigDecimal("3000.01"), Channel.ECOMMERCE, "5411", "ZA")).status())
                .isEqualTo(OutcomeStatus.NOT_MATCHED);
    }

    /** The reason names the configured set, in a stable order, because an agent reads it. */
    @Test
    void theReasonNamesTheChannelsInAStableOrder() {
        Rule rule = contributoryRule(RuleType.CHANNEL_AMOUNT,
                "{\"channels\":[\"TRANSFER\",\"ECOMMERCE\"],\"threshold\":\"5000.00\"}");
        ChannelAmountEvaluator evaluator = new ChannelAmountEvaluator(parameters);

        assertThat(evaluator.evaluate(rule, event(new BigDecimal("10.00"), Channel.CARD_PRESENT, "5411", "ZA")).reason())
                .isEqualTo("Channel CARD_PRESENT is not in [ECOMMERCE, TRANSFER]");
    }

    @Test
    void outcomesPinTheRuleVersionThatProducedThem() {
        Rule rule = contributoryRule(RuleType.AMOUNT_THRESHOLD, "{\"threshold\":\"10.00\"}");

        RuleOutcome outcome = new AmountThresholdEvaluator(parameters)
                .evaluate(rule, event(new BigDecimal("100.00"), Channel.CARD_PRESENT, "5411", "ZA"));

        assertThat(outcome.ruleId()).isEqualTo(rule.id());
        assertThat(outcome.ruleVersion()).isEqualTo(rule.version());
        assertThat(outcome.mode()).isEqualTo(rule.mode());
    }

    private static Rule contributoryRule(RuleType type, String parameters) {
        return new Rule(UUID.randomUUID(), "TEST_RULE", 3, type, RuleMode.ACTIVE,
                RuleNature.CONTRIBUTORY, null, 15, parameters, "test rule", "test typology",
                Instant.parse("2026-08-03T00:00:00Z"), null);
    }

    private static Rule decisiveRule(RuleType type, String parameters) {
        return new Rule(UUID.randomUUID(), "TEST_RULE", 3, type, RuleMode.ACTIVE,
                RuleNature.DECISIVE, Verdict.BLOCK, null, parameters, "test rule", "test typology",
                Instant.parse("2026-08-03T00:00:00Z"), null);
    }

    private static TransactionEvent event(BigDecimal amount, Channel channel, String mcc, String country) {
        return new TransactionEvent(UUID.randomUUID(), Instant.parse("2026-08-03T09:00:00Z"),
                "acct-1", new CardToken("card-1"), amount, "ZAR", "merch-1", "Merchant", mcc, country, channel,
                null, null, null, null, "groceries");
    }
}
