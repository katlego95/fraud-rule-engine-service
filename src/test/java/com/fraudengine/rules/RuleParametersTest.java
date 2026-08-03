package com.fraudengine.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleMode;
import com.fraudengine.domain.RuleNature;
import com.fraudengine.domain.RuleType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RuleParametersTest {

    record Parameters(BigDecimal threshold) {}

    private final RuleParameters parameters = new RuleParameters(JsonMapper.builder().build());

    @Test
    void readsWellFormedParameters() {
        assertThat(parameters.read(rule("{\"threshold\":\"10000.00\"}"), Parameters.class).threshold())
                .isEqualByComparingTo(new BigDecimal("10000.00"));
    }

    /**
     * Unknown properties are ignored by default, so parameters belonging to a different rule type
     * parse cleanly and leave every field null. Unchecked, that became a NullPointerException
     * inside the evaluator and an opaque 500 on the decision path.
     */
    @Test
    void rejectsParametersThatParseButLeaveAFieldMissing() {
        assertThatThrownBy(() -> parameters.read(rule("{\"nonsense\":true}"), Parameters.class))
                .isInstanceOf(InvalidRuleParametersException.class)
                .hasMessageContaining("TEST_RULE")
                .hasRootCauseMessage("missing parameter 'threshold'");
    }

    @Test
    void rejectsParametersThatAreNotJsonAtAll() {
        assertThatThrownBy(() -> parameters.read(rule("not json"), Parameters.class))
                .isInstanceOf(InvalidRuleParametersException.class);
    }

    private static Rule rule(String parameters) {
        return new Rule(UUID.randomUUID(), "TEST_RULE", 1, RuleType.AMOUNT_THRESHOLD,
                RuleMode.ACTIVE, RuleNature.CONTRIBUTORY, null, 15, parameters, "test", "test",
                Instant.parse("2026-08-03T00:00:00Z"), null);
    }
}
