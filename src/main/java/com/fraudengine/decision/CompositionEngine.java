package com.fraudengine.decision;

import com.fraudengine.domain.RuleNature;
import com.fraudengine.domain.RuleOutcome;
import com.fraudengine.domain.Verdict;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Combines rule outcomes into one verdict: the most severe of what the decisive rules demanded
 * and what the accumulated score earned.
 *
 * <p>Pure precedence is maximally explainable but cannot say "three weak signals together are
 * suspicious". Pure scoring says it but turns every verdict into an argument about one weight.
 * The hybrid keeps hard signals hard and lets weak ones accumulate, at the cost of a composition
 * rule that has to be documented and tested at its boundaries.
 */
@Component
public class CompositionEngine {

    private final ScoreBands bands;

    CompositionEngine(ScoreBands bands) {
        this.bands = bands;
    }

    public Composition compose(List<RuleOutcome> outcomes) {
        Verdict decisiveVerdict = outcomes.stream()
                .filter(RuleOutcome::countsTowardsVerdict)
                .filter(outcome -> outcome.nature() == RuleNature.DECISIVE)
                .map(RuleOutcome::verdict)
                // No decisive rule matched is APPROVE, not "no opinion" — a max over nothing needs
                // a stated identity or the composition has a hole at its most common case.
                .reduce(Verdict.APPROVE, Verdict::mostSevere);

        int totalScore = outcomes.stream()
                .filter(RuleOutcome::countsTowardsVerdict)
                .filter(outcome -> outcome.nature() == RuleNature.CONTRIBUTORY)
                .mapToInt(RuleOutcome::contribution)
                .sum();

        Verdict scoreVerdict = bands.verdictFor(totalScore);

        return new Composition(
                Verdict.mostSevere(decisiveVerdict, scoreVerdict),
                decisiveVerdict,
                scoreVerdict,
                totalScore,
                bands);
    }
}
