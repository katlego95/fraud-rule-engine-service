package com.fraudengine.domain;

public enum RuleNature {
    /** Emits a verdict directly when it matches. */
    DECISIVE,

    /** Adds weight to the risk score when it matches. */
    CONTRIBUTORY
}
