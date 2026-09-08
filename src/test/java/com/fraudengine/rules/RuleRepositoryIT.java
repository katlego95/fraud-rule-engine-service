package com.fraudengine.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fraudengine.PostgresIntegrationTest;
import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleMode;
import com.fraudengine.domain.RuleNature;
import com.fraudengine.domain.RuleType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class RuleRepositoryIT extends PostgresIntegrationTest {

    @Autowired
    private RuleRepository repository;

    @Autowired
    private JdbcClient jdbc;

    /**
     * Asserts what must be true of every evaluable rule, not how many there are. A census —
     * "there are exactly eight, and here they are" — has to be edited every time a rule is added,
     * and its failure message tells you the new number, so it gets updated without thought. These
     * assertions hold for eight rules and for eight thousand.
     *
     * <p>The baseline check is deliberately {@code contains} and not {@code containsExactly}:
     * adding a rule is routine and should not fail the build, but a seeded rule silently
     * disappearing is a fraud control switched off and must.
     */
    @Test
    void everyEvaluableRuleIsCurrentAndDocumented() {
        List<Rule> evaluable = repository.findEvaluable();

        assertThat(evaluable).extracting(Rule::code).contains(
                "HIGH_AMOUNT", "HIGH_RISK_MCC", "BLOCKED_COUNTRY", "CNP_HIGH_AMOUNT",
                "CARD_TXN_VELOCITY", "MERCHANT_SPREAD_VELOCITY", "ACCOUNT_AMOUNT_VELOCITY",
                "GEO_IMPOSSIBLE", "IP_CARD_SPREAD", "DEVICE_ACCOUNT_SPREAD");
        assertThat(evaluable).allSatisfy(rule -> {
            assertThat(rule.supersededAt()).isNull();
            assertThat(rule.description()).isNotBlank();
            assertThat(rule.typology()).isNotBlank();
        });
    }

    @Test
    void geoRuleWasVersionedByMigrationRatherThanEdited() {
        // V5 supersedes version 1 and inserts version 2 carrying the lookback parameter, which is
        // the versioning mechanism exercised for real rather than only in tests.
        assertThat(repository.findHistory("GEO_IMPOSSIBLE")).hasSize(2);
        assertThat(current("GEO_IMPOSSIBLE").version()).isEqualTo(2);
        assertThat(current("GEO_IMPOSSIBLE").parameters()).contains("lookbackHours");
        assertThat(repository.findHistory("GEO_IMPOSSIBLE").getLast().supersededAt()).isNotNull();
    }

    @Test
    void cnpRuleWasWidenedByVersioningRatherThanRenaming() {
        // V8 supersedes version 1 and inserts version 2 covering both card-not-present channels.
        // The code is unchanged on purpose: it is denormalised onto every decision_rule_outcomes
        // row, so renaming would orphan the history of every decision the rule has influenced.
        assertThat(repository.findHistory("CNP_HIGH_AMOUNT")).hasSize(2);
        assertThat(current("CNP_HIGH_AMOUNT").version()).isEqualTo(2);
        assertThat(current("CNP_HIGH_AMOUNT").parameters()).contains("ECOMMERCE").contains("TRANSFER");
        assertThat(repository.findHistory("CNP_HIGH_AMOUNT").getLast().supersededAt()).isNotNull();
    }

    @Test
    void seededWeightsCrossTheBandsAsIntended() {
        int highAmount = weightOf("HIGH_AMOUNT");
        int highRiskMcc = weightOf("HIGH_RISK_MCC");
        int cnp = weightOf("CNP_HIGH_AMOUNT");
        int spread = weightOf("MERCHANT_SPREAD_VELOCITY");

        assertThat(highAmount + highRiskMcc).isLessThan(40);
        assertThat(highAmount + cnp).isBetween(40, 69);
        assertThat(highAmount + highRiskMcc + cnp + spread).isGreaterThanOrEqualTo(70);
    }

    @Test
    void decisiveRulesCarryAVerdictAndContributoryRulesCarryAWeight() {
        assertThat(repository.findEvaluable()).allSatisfy(rule -> {
            if (rule.nature() == RuleNature.DECISIVE) {
                assertThat(rule.verdict()).isNotNull();
                assertThat(rule.weight()).isNull();
            } else {
                assertThat(rule.weight()).isNotNull();
                assertThat(rule.verdict()).isNull();
            }
        });
    }

    @Test
    void newVersionSupersedesThePriorOneAndIncrements() {
        Rule original = current("HIGH_AMOUNT");
        int evaluableBefore = repository.findEvaluable().size();

        Rule updated = repository.insertNextVersion(new Rule(
                null, "HIGH_AMOUNT", 0, RuleType.AMOUNT_THRESHOLD, RuleMode.ACTIVE,
                RuleNature.CONTRIBUTORY, null, 20, "{\"threshold\":\"7500.00\"}",
                "Transaction amount above R7,500.", "General anomaly", null, null));

        assertThat(updated.version()).isEqualTo(original.version() + 1);
        assertThat(updated.supersededAt()).isNull();
        assertThat(repository.findById(original.id()).orElseThrow().supersededAt()).isNotNull();
        assertThat(repository.findHistory("HIGH_AMOUNT")).hasSize(2);

        // The point is that a new version replaces its predecessor rather than joining it, so the
        // relationship is what matters: the evaluation set is the same size, not that it is eight.
        assertThat(repository.findEvaluable()).hasSize(evaluableBefore);
    }

    @Test
    void modeChangeIsRecordedAsATransition() {
        Rule rule = current("HIGH_AMOUNT");

        repository.changeMode(rule.id(), RuleMode.SHADOW);

        assertThat(repository.findById(rule.id()).orElseThrow().mode()).isEqualTo(RuleMode.SHADOW);
        assertThat(transitionsFor(rule.id())).containsExactly("ACTIVE->SHADOW");
    }

    @Test
    void disabledRuleLeavesTheEvaluationSet() {
        int evaluableBefore = repository.findEvaluable().size();
        int currentBefore = repository.findCurrent().size();

        repository.changeMode(current("HIGH_AMOUNT").id(), RuleMode.DISABLED);

        // Disabling removes exactly one rule from evaluation and none from history: the rule still
        // exists and is still current, it just no longer runs. Asserting the delta says that;
        // asserting "seven" only says it while there happen to be eight.
        assertThat(repository.findEvaluable()).hasSize(evaluableBefore - 1);
        assertThat(repository.findCurrent()).hasSize(currentBefore);
    }

    @Test
    void redundantModeChangeWritesNothing() {
        Rule rule = current("HIGH_AMOUNT");

        repository.changeMode(rule.id(), RuleMode.ACTIVE);

        assertThat(transitionsFor(rule.id())).isEmpty();
    }

    @Test
    void modeChangeOnAnUnknownRuleFails() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> repository.changeMode(unknown, RuleMode.SHADOW))
                .isInstanceOf(RuleNotFoundException.class);
    }

    private Rule current(String code) {
        return repository.findCurrent().stream()
                .filter(rule -> rule.code().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private int weightOf(String code) {
        return current(code).weight();
    }

    private List<String> transitionsFor(UUID ruleId) {
        return jdbc.sql("""
                select from_mode || '->' || to_mode from rule_mode_transitions
                where rule_id = :ruleId order by changed_at
                """)
                .param("ruleId", ruleId)
                .query(String.class)
                .list();
    }
}
