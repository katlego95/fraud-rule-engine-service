package com.fraudengine.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.fraudengine.PostgresIntegrationTest;
import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleMode;
import com.fraudengine.domain.RuleNature;
import com.fraudengine.domain.RuleType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The refresh path as it actually runs, without a transaction to roll back.
 *
 * <p>Deliberately not {@code @Transactional}. Every other test here is, which means their writes
 * never commit and the AFTER_COMMIT listener never fires — the base class stands in for it. That
 * stand-in would hide a broken listener, so this test commits for real and cleans up after itself.
 */
class RuleSnapshotIT extends PostgresIntegrationTest {

    private static final String CODE = "SNAPSHOT_PROBE";

    @Autowired
    private RuleService ruleService;

    @Autowired
    private RuleSnapshot snapshot;

    @Autowired
    private EvaluableRules evaluableRules;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void removeTheProbe() {
        jdbc.sql("delete from rule_mode_transitions where rule_id in (select id from rules where code = :code)")
                .param("code", CODE).update();
        jdbc.sql("delete from rules where code = :code").param("code", CODE).update();
        snapshot.refresh();
    }

    @Test
    void aCommittedRuleReachesTheSnapshotWithoutAnyoneAskingForIt() {
        assertThat(codesInSnapshot()).doesNotContain(CODE);

        ruleService.create(probe());

        // No refresh call here on purpose: the commit is what has to trigger it.
        assertThat(codesInSnapshot()).contains(CODE);
    }

    @Test
    void aCommittedModeChangeReachesTheSnapshotToo() {
        Rule created = ruleService.create(probe());
        assertThat(modeInSnapshot()).isEqualTo(RuleMode.SHADOW);

        ruleService.changeMode(created.id(), RuleMode.DISABLED);

        // DISABLED leaves the evaluable set entirely, which is the change worth propagating fast.
        assertThat(codesInSnapshot()).doesNotContain(CODE);
    }

    /** The interface the decision path depends on is the snapshot, not a query per decision. */
    @Test
    void theDecisionPathReadsTheSnapshot() {
        assertThat(evaluableRules).isSameAs(snapshot);
    }

    private java.util.List<String> codesInSnapshot() {
        return evaluableRules.current().stream().map(Rule::code).toList();
    }

    private RuleMode modeInSnapshot() {
        return evaluableRules.current().stream()
                .filter(rule -> rule.code().equals(CODE))
                .findFirst().orElseThrow().mode();
    }

    private static Rule probe() {
        return Rule.definition(CODE, RuleType.AMOUNT_THRESHOLD, RuleMode.SHADOW,
                RuleNature.CONTRIBUTORY, null, 5, "{\"threshold\":\"999999.00\"}",
                "Probe rule for snapshot propagation.", "Testing");
    }
}
