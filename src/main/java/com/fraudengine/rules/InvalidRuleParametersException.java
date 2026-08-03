package com.fraudengine.rules;

import com.fraudengine.domain.RuleType;

public class InvalidRuleParametersException extends RuntimeException {

    public InvalidRuleParametersException(String code, RuleType type, Throwable cause) {
        super("Rule %s has parameters that are not valid for type %s".formatted(code, type), cause);
    }
}
