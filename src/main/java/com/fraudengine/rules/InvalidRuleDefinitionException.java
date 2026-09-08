package com.fraudengine.rules;

import com.fraudengine.domain.RuleType;

/**
 * A rule was submitted whose parameters do not fit its type.
 *
 * <p>Distinct from {@link InvalidRuleParametersException}, which means a rule already in the
 * database could not be read on the decision path. That one is a server fault by the time it is
 * seen; this one is the caller's, and is answered before anything is stored.
 */
public class InvalidRuleDefinitionException extends RuntimeException {

    public InvalidRuleDefinitionException(String code, RuleType type, Throwable cause) {
        super("Rule %s does not carry valid %s parameters: %s".formatted(code, type,
                rootMessage(cause)), cause);
    }

    /**
     * The parse failure names the offending field — "missing parameter 'threshold'" — but it sits
     * at the bottom of the cause chain. Reporting the top of the chain would tell the caller only
     * that something was wrong, which is what they already knew.
     */
    private static String rootMessage(Throwable cause) {
        Throwable root = cause;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
