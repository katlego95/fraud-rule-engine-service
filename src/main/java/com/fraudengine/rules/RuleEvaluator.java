package com.fraudengine.rules;

import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleOutcome;
import com.fraudengine.domain.RuleType;
import com.fraudengine.domain.TransactionEvent;

/** One evaluator per rule type. Implementations are stateless and safe to share. */
public interface RuleEvaluator {

    RuleType type();

    RuleOutcome evaluate(Rule rule, TransactionEvent event);
}
