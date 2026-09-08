package com.fraudengine.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fraudengine.PostgresIntegrationTest;
import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleMode;
import com.fraudengine.domain.RuleNature;
import com.fraudengine.domain.RuleType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** ADR 0008 enforced on the write path rather than left to the seed migration. */
@Transactional
class RuleServiceIT extends PostgresIntegrationTest {

    @Autowired
    private RuleService service;

    @Autowired
    private RuleRepository rules;

    @Test
    void aNewRuleIsAcceptedInShadow() {
        Rule created = service.create(definition("BRAND_NEW", RuleMode.SHADOW, "8000.00"));

        assertThat(created.version()).isEqualTo(1);
        assertThat(created.mode()).isEqualTo(RuleMode.SHADOW);
    }

    @Test
    void aNewRuleSubmittedAsActiveIsRejected() {
        assertThatThrownBy(() -> service.create(definition("STRAIGHT_TO_ACTIVE", RuleMode.ACTIVE, "8000.00")))
                .isInstanceOf(InvalidRuleModeException.class)
                .hasMessageContaining("is new")
                .hasMessageContaining("SHADOW");

        assertThat(rules.findCurrentByCode("STRAIGHT_TO_ACTIVE")).isEmpty();
    }

    @Test
    void aNewRuleSubmittedAsDisabledIsAlsoRejected() {
        assertThatThrownBy(() -> service.create(definition("STRAIGHT_TO_DISABLED", RuleMode.DISABLED, "8000.00")))
                .isInstanceOf(InvalidRuleModeException.class);
    }

    /**
     * The defect this exists to prevent. HIGH_AMOUNT is ACTIVE; a new version submitted as SHADOW
     * would supersede it and leave the rule evaluating but contributing nothing — a fraud control
     * switched off as a side effect of editing a threshold, reported as a success.
     */
    @Test
    void editingALiveRuleIntoShadowIsRejectedRatherThanSilentlyDisablingIt() {
        Rule live = rules.findCurrentByCode("HIGH_AMOUNT").orElseThrow();
        assertThat(live.mode()).isEqualTo(RuleMode.ACTIVE);

        assertThatThrownBy(() -> service.create(definition("HIGH_AMOUNT", RuleMode.SHADOW, "8000.00")))
                .isInstanceOf(InvalidRuleModeException.class)
                .hasMessageContaining("currently ACTIVE");

        Rule after = rules.findCurrentByCode("HIGH_AMOUNT").orElseThrow();
        assertThat(after.id()).isEqualTo(live.id());
        assertThat(after.version()).isEqualTo(live.version());
        assertThat(after.mode()).isEqualTo(RuleMode.ACTIVE);
    }

    @Test
    void editingALiveRuleKeepingItsModeIsAccepted() {
        Rule before = rules.findCurrentByCode("HIGH_AMOUNT").orElseThrow();

        Rule updated = service.create(definition("HIGH_AMOUNT", RuleMode.ACTIVE, "8000.00"));

        assertThat(updated.version()).isEqualTo(before.version() + 1);
        assertThat(updated.mode()).isEqualTo(RuleMode.ACTIVE);
        assertThat(updated.parameters()).contains("8000.00");
        assertThat(rules.findById(before.id()).orElseThrow().supersededAt()).isNotNull();
    }

    /** A shadow rule stays editable in shadow — that is how a threshold is tuned before promotion. */
    @Test
    void editingAShadowRuleKeepingItInShadowIsAccepted() {
        assertThatCode(() -> service.create(definition("IP_CARD_SPREAD", RuleMode.SHADOW,
                "{\"maxCount\":3,\"windowMinutes\":10}", RuleType.IP_CARD_SPREAD_VELOCITY)))
                .doesNotThrowAnyException();
    }

    /** Promotion goes through the mode endpoint, which records the transition. */
    @Test
    void promotionIsStillPossibleThroughTheModeEndpoint() {
        Rule created = service.create(definition("PROMOTE_ME", RuleMode.SHADOW, "8000.00"));

        Rule promoted = rules.changeMode(created.id(), RuleMode.ACTIVE);

        assertThat(promoted.mode()).isEqualTo(RuleMode.ACTIVE);
        assertThat(promoted.version()).isEqualTo(created.version());
    }

    /** Parameter validation still runs, and runs before the mode check. */
    @Test
    void malformedParametersAreStillRejected() {
        assertThatThrownBy(() -> service.create(definition("TYPO_RULE", RuleMode.SHADOW, "{\"treshold\":\"1.00\"}",
                RuleType.AMOUNT_THRESHOLD)))
                .isInstanceOf(InvalidRuleDefinitionException.class);
    }

    private static Rule definition(String code, RuleMode mode, String threshold) {
        return definition(code, mode, "{\"threshold\":\"" + threshold + "\"}", RuleType.AMOUNT_THRESHOLD);
    }

    private static Rule definition(String code, RuleMode mode, String parameters, RuleType type) {
        return Rule.definition(code, type, mode, RuleNature.CONTRIBUTORY, null, 15, parameters,
                "A rule under test.", "Testing");
    }
}
