package com.fraudengine.rules;

import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleOutcome;
import com.fraudengine.domain.RuleType;
import com.fraudengine.domain.TransactionEvent;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Routes each rule to the evaluator for its type and collects the outcomes. */
@Component
public class RuleEngine {

    private final Map<RuleType, RuleEvaluator> evaluators;

    RuleEngine(List<RuleEvaluator> evaluators) {
        this.evaluators = evaluators.stream()
                .collect(Collectors.toUnmodifiableMap(RuleEvaluator::type, Function.identity()));

        EnumSet<RuleType> unimplemented = EnumSet.allOf(RuleType.class);
        unimplemented.removeAll(this.evaluators.keySet());
        if (!unimplemented.isEmpty()) {
            throw new IllegalStateException(
                    "No RuleEvaluator is registered for rule type(s) %s. A rule of that type would "
                            .formatted(unimplemented)
                            + "otherwise be skipped at evaluation time, silently removing a fraud "
                            + "control from every decision, so the application refuses to start.");
        }
    }

    public List<RuleOutcome> evaluate(List<Rule> rules, TransactionEvent event) {
        return rules.stream().map(rule -> evaluatorFor(rule).evaluate(rule, event)).toList();
    }

    /**
     * A rule type with no evaluator is a deployment error — not a rule that did not match, and not
     * a rule that could not be evaluated for want of data. Startup already refuses this case; the
     * check here names the offending rule rather than throwing a null pointer if a type is ever
     * introduced after the context is built.
     */
    private RuleEvaluator evaluatorFor(Rule rule) {
        RuleEvaluator evaluator = evaluators.get(rule.type());
        if (evaluator == null) {
            throw new IllegalStateException(
                    "Rule %s (version %d) has type %s, for which no evaluator is registered. Refusing "
                            .formatted(rule.code(), rule.version(), rule.type())
                            + "to decide rather than silently omitting the rule.");
        }
        return evaluator;
    }
}
