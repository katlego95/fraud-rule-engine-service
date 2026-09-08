package com.fraudengine.rules;

import com.fraudengine.domain.Rule;
import java.lang.reflect.RecordComponent;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a rule's JSONB parameters into the record its evaluator expects.
 *
 * <p>The same parse is the write-time validation: if the parameters do not deserialise into that
 * type's record, the rule is invalid. {@link RuleValidator} runs it before an insert so a
 * malformed rule is rejected with a 400 rather than failing on the decision path. Rules written by
 * a migration or by direct SQL still bypass that check, which is why this remains the last line of
 * defence. See ADR 0007.
 */
@Component
public class RuleParameters {

    private final JsonMapper json;

    RuleParameters(JsonMapper json) {
        this.json = json;
    }

    public <T> T read(Rule rule, Class<T> type) {
        T parsed;
        try {
            parsed = json.readValue(rule.parameters(), type);
        } catch (RuntimeException e) {
            throw new InvalidRuleParametersException(rule.code(), rule.type(), e);
        }
        requireEveryParameterPresent(rule, type, parsed);
        return parsed;
    }

    /**
     * Unknown properties are ignored by default, so parameters for the wrong rule type parse
     * happily and leave every field null. Left unchecked that surfaces as a NullPointerException
     * deep inside an evaluator — an opaque 500 on the decision path, for what is really a
     * misconfigured rule. Naming the missing parameter turns it into an answerable error.
     */
    private <T> void requireEveryParameterPresent(Rule rule, Class<T> type, T parsed) {
        if (!type.isRecord()) {
            return;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            if (component.getType().isPrimitive()) {
                continue;
            }
            if (valueOf(component, parsed) == null) {
                throw new InvalidRuleParametersException(rule.code(), rule.type(),
                        new IllegalArgumentException("missing parameter '" + component.getName() + "'"));
            }
        }
    }

    private static Object valueOf(RecordComponent component, Object parsed) {
        try {
            return component.getAccessor().invoke(parsed);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not read parameter " + component.getName(), e);
        }
    }
}
