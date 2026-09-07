package com.fraudengine.rules;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fraudengine.PostgresIntegrationTest;
import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleMode;
import com.fraudengine.domain.RuleNature;
import com.fraudengine.domain.RuleType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The parse that used to happen only on the decision path, now applied to the write. */
class RuleValidatorIT extends PostgresIntegrationTest {

    @Autowired
    private RuleValidator validator;

    @Test
    void wellFormedParametersAreAccepted() {
        assertThatCode(() -> validator.validate(rule(RuleType.AMOUNT_THRESHOLD, "{\"threshold\":\"8000.00\"}")))
                .doesNotThrowAnyException();
    }

    /** The typo that used to reach the database and fail on somebody else's transaction. */
    @Test
    void misspelledParameterIsRejected() {
        assertThatThrownBy(() -> validator.validate(rule(RuleType.AMOUNT_THRESHOLD, "{\"treshold\":\"8000.00\"}")))
                .isInstanceOf(InvalidRuleDefinitionException.class)
                .hasMessageContaining("threshold");
    }

    @Test
    void parametersForAnotherRuleTypeAreRejected() {
        assertThatThrownBy(() -> validator.validate(rule(RuleType.CARD_COUNT_VELOCITY, "{\"threshold\":\"8000.00\"}")))
                .isInstanceOf(InvalidRuleDefinitionException.class);
    }

    @Test
    void unparseableJsonIsRejected() {
        assertThatThrownBy(() -> validator.validate(rule(RuleType.AMOUNT_THRESHOLD, "not json")))
                .isInstanceOf(InvalidRuleDefinitionException.class);
    }

    /** Every rule type must be validatable, so a new evaluator cannot arrive without a parameter record. */
    @Test
    void everyRuleTypeHasAParameterRecordToValidateAgainst() {
        for (RuleType type : RuleType.values()) {
            assertThatThrownBy(() -> validator.validate(rule(type, "{}")))
                    .as("rule type %s", type)
                    .isInstanceOf(InvalidRuleDefinitionException.class);
        }
    }

    private static Rule rule(RuleType type, String parameters) {
        return Rule.definition("TEST_RULE", type, RuleMode.SHADOW, RuleNature.CONTRIBUTORY,
                null, 10, parameters, "A rule under test.", "Testing");
    }
}
