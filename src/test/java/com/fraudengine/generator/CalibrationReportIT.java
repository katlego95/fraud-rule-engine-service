package com.fraudengine.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fraudengine.PostgresIntegrationTest;
import com.fraudengine.decision.DecisionService;
import com.fraudengine.domain.Decision;
import com.fraudengine.domain.OutcomeStatus;
import com.fraudengine.domain.RuleOutcome;
import com.fraudengine.domain.TransactionEvent;
import com.fraudengine.domain.Verdict;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Measures the engine against the generator's labels.
 *
 * <p>The labels are ground truth, so this is the difference between "the thresholds look sensible"
 * and "here is what they caught and what they cost". The numbers describe a deliberately
 * constructed corpus, so they demonstrate the calibration method rather than production accuracy —
 * that limitation is printed alongside the table so it travels with the numbers.
 */
@Transactional
class CalibrationReportIT extends PostgresIntegrationTest {

    /**
     * A legitimate transaction receiving anything other than APPROVE is the cost of the rule set:
     * a declined grocery purchase or a call-centre ticket. Held to under one percent.
     */
    private static final double MAX_FALSE_POSITIVE_RATE = 1.0;

    @Autowired
    private SyntheticCorpus corpus;

    @Autowired
    private DecisionService service;

    @Autowired
    private com.fraudengine.rules.RuleRepository rules;

    @Test
    void everyInjectedTypologyIsCaughtByItsIntendedRuleAndBackgroundStaysQuiet() throws IOException {
        Corpus generated = corpus.generate();

        Map<String, Integer> fraudCaughtByRule = new TreeMap<>();
        Map<String, Integer> legitimateFlaggedByRule = new TreeMap<>();
        List<ScenarioOutcome> scenarioOutcomes = new ArrayList<>();

        // Event-time order across the whole corpus, so velocity windows see the history a real
        // deployment would have seen.
        Map<java.util.UUID, Decision> decisions = new LinkedHashMap<>();
        for (TransactionEvent event : generated.allEvents()) {
            decisions.put(event.eventId(), service.decide(event).decision());
        }

        int flaggedBackground = 0;
        for (TransactionEvent event : generated.background()) {
            Decision decision = decisions.get(event.eventId());
            if (decision.verdict() != Verdict.APPROVE) {
                flaggedBackground++;
            }
            for (RuleOutcome outcome : matched(decision)) {
                legitimateFlaggedByRule.merge(outcome.ruleCode(), 1, Integer::sum);
            }
        }

        for (LabelledScenario scenario : generated.scenarios()) {
            Decision decision = decisions.get(scenario.triggeringEvent().eventId());
            List<String> matchedCodes = matched(decision).stream().map(RuleOutcome::ruleCode).toList();

            boolean decisive = rules.findCurrent().stream()
                    .filter(rule -> rule.code().equals(scenario.expectedRuleCode()))
                    .anyMatch(com.fraudengine.domain.Rule::isDecisive);

            scenarioOutcomes.add(new ScenarioOutcome(scenario, decision.verdict(), matchedCodes, decisive));
            matchedCodes.forEach(code -> fraudCaughtByRule.merge(code, 1, Integer::sum));
        }

        double falsePositiveRate = 100.0 * flaggedBackground / generated.background().size();

        String report = render(generated, scenarioOutcomes, fraudCaughtByRule,
                legitimateFlaggedByRule, flaggedBackground, falsePositiveRate);
        System.out.println(report);
        Files.writeString(Path.of("target", "calibration-report.md"), report);

        assertThat(scenarioOutcomes)
                .as("every injected typology is caught by the rule it is labelled with")
                .allSatisfy(outcome -> assertThat(outcome.matchedCodes())
                        .as("%s should be caught by %s", outcome.scenario().name(),
                                outcome.scenario().expectedRuleCode())
                        .contains(outcome.scenario().expectedRuleCode()));

        assertThat(scenarioOutcomes)
                .as("no injected typology is caught only by an unintended rule, which would mean "
                        + "the rule set works by accident")
                .allSatisfy(outcome -> assertThat(outcome.matchedCodes())
                        .isNotEmpty()
                        .doesNotContainNull());

        // A decisive rule must act. A contributory one legitimately may not: its whole design is
        // that one weak signal is not enough, so asserting otherwise would be asserting against
        // the hybrid model rather than testing it.
        assertThat(scenarioOutcomes.stream().filter(ScenarioOutcome::intendedRuleIsDecisive).toList())
                .as("a typology caught by a decisive rule is acted on, not merely noticed")
                .isNotEmpty()
                .allSatisfy(outcome -> assertThat(outcome.verdict()).isNotEqualTo(Verdict.APPROVE));

        assertThat(falsePositiveRate)
                .as("false positive rate on legitimate background traffic")
                .isLessThan(MAX_FALSE_POSITIVE_RATE);
    }

    private static List<RuleOutcome> matched(Decision decision) {
        return decision.outcomes().stream()
                .filter(outcome -> outcome.status() == OutcomeStatus.MATCHED)
                .toList();
    }

    private record ScenarioOutcome(LabelledScenario scenario, Verdict verdict,
            List<String> matchedCodes, boolean intendedRuleIsDecisive) {}

    private static String render(Corpus corpus, List<ScenarioOutcome> scenarios,
            Map<String, Integer> fraudCaught, Map<String, Integer> legitimateFlagged,
            int flaggedBackground, double falsePositiveRate) {

        StringBuilder out = new StringBuilder();
        out.append("## Calibration report\n\n");
        out.append("Generated by `CalibrationReportIT` against the fixed-seed corpus: **")
                .append(corpus.background().size()).append(" legitimate transactions** across 50 accounts over 30 days, plus **")
                .append(corpus.scenarios().size()).append(" injected fraud scenarios**.\n\n");

        out.append("### Injected typologies\n\n");
        out.append("| Scenario | Typology | Intended rule | Detected | Verdict | Also matched |\n");
        out.append("|---|---|---|---|---|---|\n");
        for (ScenarioOutcome outcome : scenarios) {
            List<String> others = outcome.matchedCodes().stream()
                    .filter(code -> !code.equals(outcome.scenario().expectedRuleCode()))
                    .toList();
            out.append("| `").append(outcome.scenario().name()).append("` | ")
                    .append(outcome.scenario().typology()).append(" | `")
                    .append(outcome.scenario().expectedRuleCode())
                    .append(outcome.intendedRuleIsDecisive() ? "` (decisive)" : "` (contributory)")
                    .append(" | ")
                    .append(outcome.matchedCodes().contains(outcome.scenario().expectedRuleCode()) ? "yes" : "**NO**")
                    .append(" | ").append(outcome.verdict()).append(" | ")
                    .append(others.isEmpty() ? "—" : String.join(", ", others)).append(" |\n");
        }

        out.append("\nDetection and action are different things, and the table separates them ")
                .append("deliberately. A decisive rule acts on its own. A contributory rule records ")
                .append("what it saw and adds weight — by design one weak signal is not enough, so a ")
                .append("typology caught only by a contributory rule can be detected and still ")
                .append("approve. `merchant-spread` is exactly that case: it is recorded on the ")
                .append("decision at its full weight, and it would combine with any other signal to ")
                .append("cross a band. Forcing it to act alone would mean abandoning the hybrid model.\n");

        out.append("\n### Per rule\n\n");
        out.append("| Rule | Fraud scenarios caught | Legitimate transactions flagged | False positive rate |\n");
        out.append("|---|---|---|---|\n");
        java.util.Set<String> allRules = new java.util.TreeSet<>();
        allRules.addAll(fraudCaught.keySet());
        allRules.addAll(legitimateFlagged.keySet());
        for (String rule : allRules) {
            int flagged = legitimateFlagged.getOrDefault(rule, 0);
            out.append("| `").append(rule).append("` | ")
                    .append(fraudCaught.getOrDefault(rule, 0)).append(" | ")
                    .append(flagged).append(" | ")
                    .append("%.2f%%".formatted(100.0 * flagged / corpus.background().size()))
                    .append(" |\n");
        }

        out.append("\n**Overall false positive rate: ")
                .append("%.2f%%".formatted(falsePositiveRate))
                .append("** — ").append(flaggedBackground).append(" of ")
                .append(corpus.background().size())
                .append(" legitimate transactions received a verdict other than APPROVE.\n\n");

        out.append("A rule appearing in the \"legitimate transactions flagged\" column has not ")
                .append("necessarily caused a false positive: a contributory rule can match without ")
                .append("its weight reaching a band. The overall rate counts verdicts, which is the ")
                .append("number that costs money.\n\n");

        out.append("> **Limitation.** This corpus is synthetic and deliberately constructed, so these ")
                .append("numbers demonstrate the calibration *method* rather than production-representative ")
                .append("accuracy. Real threshold tuning would require historical transaction data.\n");

        return out.toString();
    }
}
