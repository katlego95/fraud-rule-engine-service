package com.fraudengine.domain;

/**
 * Selects the evaluator and, with it, the shape of the rule's JSONB parameters.
 *
 * <p>One type per evaluation shape rather than one per the brief's four families. Collapsing
 * HIGH_AMOUNT and HIGH_RISK_MCC into a single "threshold" evaluator would mean a parameter that
 * switches behaviour between comparing a number and testing set membership — an abstraction that
 * earns nothing and costs a branch. The families remain a useful way to describe the rule set;
 * they are not a useful way to implement it.
 */
public enum RuleType {
    AMOUNT_THRESHOLD,
    MCC_SET,
    COUNTRY_BLOCKLIST,
    CHANNEL_AMOUNT,
    CARD_COUNT_VELOCITY,
    MERCHANT_SPREAD_VELOCITY,
    ACCOUNT_AMOUNT_VELOCITY,
    GEO_SPEED
}
