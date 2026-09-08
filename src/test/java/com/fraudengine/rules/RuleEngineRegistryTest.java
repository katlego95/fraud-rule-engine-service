package com.fraudengine.rules;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleOutcome;
import com.fraudengine.domain.RuleType;
import com.fraudengine.domain.TransactionEvent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The guard that stops a rule type being half-added.
 *
 * <p>A rule whose type has no evaluator would be skipped at evaluation time — a fraud control
 * removed from every decision while the response still reports a clean APPROVE. The engine refuses
 * to start instead. That behaviour had no test, so a refactor could have removed it silently.
 *
 * <p>Plain unit test: the constructor is package-private and takes the evaluator list Spring would
 * inject, so an incomplete registry is one line to construct and needs no context.
 */
class RuleEngineRegistryTest {

    @Test
    void refusesToStartWhenARuleTypeHasNoEvaluator() {
        List<RuleEvaluator> allButOne = evaluatorsFor(
                Arrays.stream(RuleType.values()).filter(t -> t != RuleType.GEO_SPEED).toList());

        assertThatThrownBy(() -> new RuleEngine(allButOne))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GEO_SPEED")
                .hasMessageContaining("refuses to start");
    }

    /** The message has to name every missing type, not just the first one found. */
    @Test
    void namesEveryUnimplementedType() {
        List<RuleEvaluator> onlyOne = evaluatorsFor(List.of(RuleType.AMOUNT_THRESHOLD));

        assertThatThrownBy(() -> new RuleEngine(onlyOne))
                .hasMessageContaining("MCC_SET")
                .hasMessageContaining("GEO_SPEED")
                .hasMessageContaining("IP_CARD_SPREAD_VELOCITY");
    }

    @Test
    void startsWhenEveryTypeIsCovered() {
        assertThatCode(() -> new RuleEngine(evaluatorsFor(Arrays.asList(RuleType.values()))))
                .doesNotThrowAnyException();
    }

    private static List<RuleEvaluator> evaluatorsFor(List<RuleType> types) {
        return types.stream().map(RuleEngineRegistryTest::stub).toList();
    }

    private static RuleEvaluator stub(RuleType type) {
        return new RuleEvaluator() {
            @Override
            public RuleType type() {
                return type;
            }

            @Override
            public Class<?> parametersType() {
                return Object.class;
            }

            @Override
            public RuleOutcome evaluate(Rule rule, TransactionEvent event) {
                throw new UnsupportedOperationException("registry test, never evaluated");
            }
        };
    }
}
