package com.fraudengine.rules;

import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleOutcome;
import com.fraudengine.domain.RuleType;
import com.fraudengine.domain.TransactionEvent;

/** One evaluator per rule type. Implementations are stateless and safe to share. */
public interface RuleEvaluator {

    RuleType type();

    /** The record this evaluator's JSONB parameters must deserialise into. */
    Class<?> parametersType();

    RuleOutcome evaluate(Rule rule, TransactionEvent event);
}
