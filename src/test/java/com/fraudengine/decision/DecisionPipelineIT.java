package com.fraudengine.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fraudengine.PostgresIntegrationTest;
import com.fraudengine.domain.CardToken;
import com.fraudengine.domain.Channel;
import com.fraudengine.domain.Decision;
import com.fraudengine.domain.OutcomeStatus;
import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleMode;
import com.fraudengine.domain.RuleOutcome;
import com.fraudengine.domain.TransactionEvent;
import com.fraudengine.domain.Verdict;
import com.fraudengine.rules.RuleRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class DecisionPipelineIT extends PostgresIntegrationTest {

    @Autowired
    private DecisionService service;

    @Autowired
    private DecisionRepository decisions;

    @Autowired
    private RuleRepository rules;

    @Test
    void ordinaryTransactionIsApprovedAndEveryRuleIsRecorded() {
        DecisionResult result = service.decide(event(new BigDecimal("120.00"), Channel.CARD_PRESENT, "5411", "ZA"));

        assertThat(result.replayed()).isFalse();
        assertThat(result.decision().verdict()).isEqualTo(Verdict.APPROVE);
        assertThat(result.decision().totalScore()).isZero();

        // Every rule that could run is recorded, so the expected count comes from the rule set
        // rather than a literal that has to be edited each time a rule is added.
        assertThat(result.decision().outcomes()).hasSameSizeAs(rules.findEvaluable());
        assertThat(result.decision().outcomes()).noneMatch(outcome -> outcome.status() == OutcomeStatus.MATCHED);
    }

    @Test
    void ruleThatCouldNotRunIsRecordedAsUnevaluableNotAsANonMatch() {
        // No coordinates, so the geo rule has nothing to compute a speed from.
        Decision decision = service.decide(
                event(new BigDecimal("120.00"), Channel.CARD_PRESENT, "5411", "ZA")).decision();

        RuleOutcome geo = outcomeFor(decision, "GEO_IMPOSSIBLE");
        assertThat(geo.status()).isEqualTo(OutcomeStatus.NOT_EVALUABLE);
        assertThat(geo.reason()).contains("No coordinates");
    }

    @Test
    void blockedCountryBlocksThroughTheDecisiveHalf() {
        Decision decision = service.decide(
                event(new BigDecimal("120.00"), Channel.ECOMMERCE, "5411", "KP")).decision();

        assertThat(decision.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(decision.decisiveVerdict()).isEqualTo(Verdict.BLOCK);
        assertThat(decision.scoreVerdict()).isEqualTo(Verdict.APPROVE);
    }

    @Test
    void weakSignalsAccumulateIntoReviewThroughTheScoringHalf() {
        // High amount (15) + high-risk MCC (15) + card-not-present above threshold (25) = 55.
        Decision decision = service.decide(
                event(new BigDecimal("12000.00"), Channel.ECOMMERCE, "7995", "ZA")).decision();

        assertThat(decision.totalScore()).isEqualTo(55);
        assertThat(decision.decisiveVerdict()).isEqualTo(Verdict.APPROVE);
        assertThat(decision.scoreVerdict()).isEqualTo(Verdict.REVIEW);
        assertThat(decision.verdict()).isEqualTo(Verdict.REVIEW);
    }

    @Test
    void decisionCarriesTheBandsInForceAndTheEngineVersion() {
        Decision decision = service.decide(event(new BigDecimal("50.00"), Channel.ATM, "5411", "ZA")).decision();

        assertThat(decision.reviewFrom()).isEqualTo(40);
        assertThat(decision.blockFrom()).isEqualTo(70);
        assertThat(decision.engineVersion()).isNotBlank();
        assertThat(decision.eventSnapshot()).contains("\"amount\":\"50.00\"");
    }

    @Test
    void shadowRuleIsRecordedWithItsWeightButChangesNothing() {
        rules.changeMode(currentRule("HIGH_AMOUNT").id(), RuleMode.SHADOW);

        // Above the high-amount threshold but below the account velocity threshold, so the only
        // rule that matches is the shadowed one and its exclusion is what the verdict proves.
        Decision decision = service.decide(
                event(new BigDecimal("12000.00"), Channel.CARD_PRESENT, "5411", "ZA")).decision();

        RuleOutcome shadow = outcomeFor(decision, "HIGH_AMOUNT");
        assertThat(shadow.mode()).isEqualTo(RuleMode.SHADOW);
        assertThat(shadow.status()).isEqualTo(OutcomeStatus.MATCHED);
        assertThat(shadow.contribution()).isEqualTo(15);
        assertThat(decision.totalScore()).isZero();
        assertThat(decision.verdict()).isEqualTo(Verdict.APPROVE);
    }

    @Test
    void resubmittingTheSameEventReturnsTheOriginalDecision() {
        TransactionEvent event = event(new BigDecimal("120.00"), Channel.CARD_PRESENT, "5411", "ZA");

        DecisionResult first = service.decide(event);
        DecisionResult second = service.decide(event);

        assertThat(second.replayed()).isTrue();
        assertThat(second.decision().decisionId()).isEqualTo(first.decision().decisionId());
        // Exact equality, not "close enough": the evaluation result and the round-tripped row must
        // agree to the microsecond, which is what the timestamp is truncated to when written.
        assertThat(second.decision().evaluatedAt()).isEqualTo(first.decision().evaluatedAt());
    }

    @Test
    void resubmittingTheSameEventIdWithADifferentBodyIsRejected() {
        TransactionEvent original = event(new BigDecimal("120.00"), Channel.CARD_PRESENT, "5411", "ZA");
        service.decide(original);

        TransactionEvent tampered = new TransactionEvent(original.eventId(), original.occurredAt(),
                original.accountId(), original.cardToken(), new BigDecimal("99000.00"),
                original.currency(), original.merchantId(), original.merchantName(),
                original.merchantCategoryCode(), original.merchantCountry(), original.channel(),
                null, null, null, null, original.category());

        assertThatThrownBy(() -> service.decide(tampered))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void decisionPinsTheRuleVersionThatFiredEvenAfterTheRuleMovesOn() {
        Rule before = currentRule("HIGH_AMOUNT");
        Decision decision = service.decide(
                event(new BigDecimal("12000.00"), Channel.CARD_PRESENT, "5411", "ZA")).decision();

        rules.insertNextVersion(new Rule(null, "HIGH_AMOUNT", 0, before.type(), RuleMode.ACTIVE,
                before.nature(), null, 99, "{\"threshold\":\"1.00\"}", "Much lower threshold",
                before.typology(), null, null));

        Decision reread = decisions.findById(decision.decisionId()).orElseThrow();
        RuleOutcome outcome = outcomeFor(reread, "HIGH_AMOUNT");

        assertThat(outcome.ruleId()).isEqualTo(before.id());
        assertThat(outcome.ruleVersion()).isEqualTo(before.version());
        assertThat(outcome.contribution()).isEqualTo(15);
        assertThat(currentRule("HIGH_AMOUNT").version()).isEqualTo(before.version() + 1);
    }

    @Test
    void auditViewReturnsEveryRuleEvaluatedNotOnlyThoseThatMatched() {
        Decision decision = service.decide(
                event(new BigDecimal("12000.00"), Channel.CARD_PRESENT, "5411", "ZA")).decision();

        Decision reread = decisions.findById(decision.decisionId()).orElseThrow();

        // The audit view must hold one outcome per evaluable rule — including the rules that did
        // not match and the rules that could not run. Comparing against the rule set states that
        // directly; a hardcoded list of codes only restates today's seed data.
        assertThat(reread.outcomes()).extracting(RuleOutcome::ruleCode)
                .containsExactlyInAnyOrderElementsOf(
                        rules.findEvaluable().stream().map(Rule::code).toList());
        assertThat(reread.outcomes()).allMatch(outcome -> !outcome.reason().isBlank());
    }

    @Test
    void decisionIsResolvableByTheCallersOwnEventId() {
        TransactionEvent event = event(new BigDecimal("120.00"), Channel.CARD_PRESENT, "5411", "ZA");
        Decision decision = service.decide(event).decision();

        assertThat(decisions.findByEventId(event.eventId()).orElseThrow().decisionId())
                .isEqualTo(decision.decisionId());
    }

    @Test
    void keysetPaginationWalksEveryRowExactlyOnce() {
        for (int i = 0; i < 7; i++) {
            service.decide(event(new BigDecimal("100.00"), Channel.CARD_PRESENT, "5411", "ZA"));
        }

        List<UUID> walked = new java.util.ArrayList<>();
        Cursor cursor = null;
        for (int page = 0; page < 10; page++) {
            List<Decision> found = decisions.search(query(cursor), 3);
            if (found.isEmpty()) {
                break;
            }
            found.forEach(decision -> walked.add(decision.decisionId()));
            Decision last = found.getLast();
            cursor = new Cursor(last.evaluatedAt(), last.decisionId());
        }

        assertThat(walked).hasSize(7).doesNotHaveDuplicates();
    }

    @Test
    void verdictFilterReturnsOnlyMatchingDecisions() {
        service.decide(event(new BigDecimal("120.00"), Channel.CARD_PRESENT, "5411", "ZA"));
        service.decide(event(new BigDecimal("120.00"), Channel.ECOMMERCE, "5411", "KP"));

        List<Decision> blocked = decisions.search(new DecisionQuery(null, null,
                List.of(Verdict.BLOCK), null, null, null, null, null), 50);

        assertThat(blocked).isNotEmpty();
        assertThat(blocked).allMatch(decision -> decision.verdict() == Verdict.BLOCK);
    }

    @Test
    void ruleCodeFilterFindsDecisionsWhereThatRuleFired() {
        service.decide(event(new BigDecimal("120.00"), Channel.CARD_PRESENT, "5411", "ZA"));
        Decision matched = service.decide(
                event(new BigDecimal("12000.00"), Channel.CARD_PRESENT, "5411", "ZA")).decision();

        List<Decision> found = decisions.search(new DecisionQuery(null, null, List.of(),
                "HIGH_AMOUNT", null, null, null, null), 50);

        assertThat(found).extracting(Decision::decisionId).contains(matched.decisionId());
        assertThat(found).hasSize(1);
    }

    @Test
    void emptyResultIsAnEmptyListNotAnError() {
        assertThat(decisions.search(new DecisionQuery("no-such-account", null, List.of(), null,
                null, null, null, null), 50)).isEmpty();
    }

    private DecisionQuery query(Cursor cursor) {
        return new DecisionQuery(null, null, List.of(), null, null, null, null, cursor);
    }

    private Rule currentRule(String code) {
        return rules.findCurrent().stream()
                .filter(rule -> rule.code().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private static RuleOutcome outcomeFor(Decision decision, String code) {
        return decision.outcomes().stream()
                .filter(outcome -> outcome.ruleCode().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private static TransactionEvent event(BigDecimal amount, Channel channel, String mcc, String country) {
        return new TransactionEvent(UUID.randomUUID(), Instant.parse("2026-08-03T09:00:00Z"),
                "acct-1", new CardToken("card-1"), amount, "ZAR", "merch-1", "Merchant", mcc, country, channel,
                null, null, null, null, "groceries");
    }
}
