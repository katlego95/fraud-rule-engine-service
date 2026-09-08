package com.fraudengine.rules;

import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleMode;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writing a rule, with the policy that governs it.
 *
 * <p>Until now the controller validated and the repository wrote, and there was nothing in between
 * worth a layer. Enforcing ADR 0008 changes that: deciding a new version's mode means reading the
 * version in force and then writing, and a read-then-write needs a transaction around it or a
 * concurrent mode change lands between the two.
 */
@Service
public class RuleService {

    private final RuleRepository rules;
    private final RuleValidator validator;

    RuleService(RuleRepository rules, RuleValidator validator) {
        this.rules = rules;
        this.validator = validator;
    }

    /**
     * Creates a rule, or the next version of an existing code.
     *
     * <p>ADR 0008 says a new rule ships in shadow, because nobody has yet seen what its threshold
     * does to real traffic. That was a convention held up by the seed migration; this enforces it.
     *
     * <p>A new version of an existing code must keep the mode in force. Creating a version
     * supersedes its predecessor, so a version submitted as SHADOW against a rule that is ACTIVE
     * would take that rule out of the verdict — the response would report success while a fraud
     * control quietly stopped acting. Editing a threshold and changing what a rule does are
     * different operations, and mode has its own endpoint precisely so they stay separate.
     */
    @Transactional
    public Rule create(Rule definition) {
        validator.validate(definition);

        Optional<Rule> current = rules.findCurrentByCode(definition.code());
        if (current.isEmpty()) {
            if (definition.mode() != RuleMode.SHADOW) {
                throw InvalidRuleModeException.newRuleMustStartInShadow(
                        definition.code(), definition.mode());
            }
        } else if (definition.mode() != current.get().mode()) {
            throw InvalidRuleModeException.versionMustKeepCurrentMode(
                    definition.code(), definition.mode(), current.get().mode());
        }

        return rules.insertNextVersion(definition);
    }
}
