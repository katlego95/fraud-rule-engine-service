package com.fraudengine.rules;

import com.fraudengine.domain.RuleMode;

/**
 * A rule was submitted in a mode the write path does not allow.
 *
 * <p>Rejected rather than silently corrected. A caller who asked for ACTIVE and was quietly given
 * SHADOW believes the rule is deciding when it is only watching, which is a worse outcome than an
 * error they can read.
 */
public class InvalidRuleModeException extends RuntimeException {

    private InvalidRuleModeException(String message) {
        super(message);
    }

    static InvalidRuleModeException newRuleMustStartInShadow(String code, RuleMode requested) {
        return new InvalidRuleModeException(
                ("Rule %s is new and was submitted as %s. A rule nobody has observed does not get to "
                        + "decide: create it as SHADOW, read what it would have done, then promote it "
                        + "with PATCH /rules/{id}/mode.").formatted(code, requested));
    }

    static InvalidRuleModeException versionMustKeepCurrentMode(
            String code, RuleMode requested, RuleMode current) {
        return new InvalidRuleModeException(
                ("Rule %s is currently %s and the new version was submitted as %s. Creating a version "
                        + "supersedes the one in force, so a differing mode would change what the rule "
                        + "does as a side effect of editing it. Submit %s, and change mode separately "
                        + "with PATCH /rules/{id}/mode.").formatted(code, current, requested, current));
    }
}
