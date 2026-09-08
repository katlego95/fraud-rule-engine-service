package com.fraudengine.rules;

import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleType;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Checks a rule's parameters before it is stored.
 *
 * <p>The parse this performs is the same one the evaluator does at decision time, moved to the
 * write. Without it a malformed rule is accepted, and the first transaction after it fails —
 * so the caller who made the mistake is told nothing and an unrelated caller gets the error.
 *
 * <p>Each evaluator declares its own parameter record, so nothing here needs a per-type list to
 * keep in step: a new rule type cannot compile without answering {@code parametersType()}.
 */
@Component
public class RuleValidator {

    private final Map<RuleType, Class<?>> parameterTypes;
    private final RuleParameters parameters;

    RuleValidator(List<RuleEvaluator> evaluators, RuleParameters parameters) {
        this.parameterTypes = evaluators.stream().collect(
                Collectors.toUnmodifiableMap(RuleEvaluator::type, RuleEvaluator::parametersType));
        this.parameters = parameters;
    }

    public void validate(Rule rule) {
        Class<?> expected = parameterTypes.get(rule.type());
        if (expected == null) {
            // Unreachable while RuleEngine refuses to start with an unregistered type; kept so a
            // future path into this class cannot silently skip the check.
            throw new IllegalStateException("No evaluator registered for rule type " + rule.type());
        }
        try {
            parameters.read(rule, expected);
        } catch (InvalidRuleParametersException e) {
            throw new InvalidRuleDefinitionException(rule.code(), rule.type(), e);
        }
    }
}
