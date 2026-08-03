package com.fraudengine.domain;

public enum RuleMode {
    /** Evaluated; contributes to the verdict and the score. */
    ACTIVE,

    /** Evaluated and recorded on the decision, but contributes to neither. */
    SHADOW,

    /** Not evaluated at all. */
    DISABLED;

    public boolean isEvaluated() {
        return this != DISABLED;
    }

    public boolean affectsVerdict() {
        return this == ACTIVE;
    }
}
