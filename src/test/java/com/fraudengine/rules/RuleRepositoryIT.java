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

    @Test
    void seedsEightRulesAllCurrentAndEvaluable() {
        List<Rule> evaluable = repository.findEvaluable();

        assertThat(evaluable).hasSize(8);
        assertThat(evaluable).extracting(Rule::code).containsExactlyInAnyOrder(
                "HIGH_AMOUNT", "HIGH_RISK_MCC", "BLOCKED_COUNTRY", "CNP_HIGH_AMOUNT",
                "CARD_TXN_VELOCITY", "MERCHANT_SPREAD_VELOCITY", "ACCOUNT_AMOUNT_VELOCITY",
                "GEO_IMPOSSIBLE");
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

        Rule updated = repository.insertNextVersion(new Rule(
                null, "HIGH_AMOUNT", 0, RuleType.AMOUNT_THRESHOLD, RuleMode.ACTIVE,
                RuleNature.CONTRIBUTORY, null, 20, "{\"threshold\":\"7500.00\"}",
                "Transaction amount above R7,500.", "General anomaly", null, null));

        assertThat(updated.version()).isEqualTo(original.version() + 1);
        assertThat(updated.supersededAt()).isNull();
        assertThat(repository.findById(original.id()).orElseThrow().supersededAt()).isNotNull();
        assertThat(repository.findHistory("HIGH_AMOUNT")).hasSize(2);
        assertThat(repository.findEvaluable()).hasSize(8);
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
        repository.changeMode(current("HIGH_AMOUNT").id(), RuleMode.DISABLED);

        assertThat(repository.findEvaluable()).hasSize(7);
        assertThat(repository.findCurrent()).hasSize(8);
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
