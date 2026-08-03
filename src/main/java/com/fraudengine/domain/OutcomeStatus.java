package com.fraudengine.domain;

/**
 * A rule that could not run must never be indistinguishable from a rule that ran and did not
 * match — a missing coordinate silently reading as "no fraud detected" is the failure mode this
 * type exists to prevent.
 */
public enum OutcomeStatus {
    MATCHED,
    NOT_MATCHED,
    NOT_EVALUABLE
}
