package com.fraudengine.rules;

import com.fraudengine.domain.Rule;
import java.util.List;

/**
 * The rule set as the decision path needs it: one question, asked on every transaction.
 *
 * <p>Separated from {@link RuleRepository} because the two callers want different things. The
 * decision path wants the evaluable set as fast as possible and can tolerate it being a moment
 * old; the rules API wants whatever is in the database right now, because a caller who has just
 * written a rule expects to read it back. Naming the narrow need means the decision path cannot
 * accidentally acquire the wider one.
 */
public interface EvaluableRules {

    /** Every rule that is current and not disabled. Never empty in a healthy service. */
    List<Rule> current();
}
