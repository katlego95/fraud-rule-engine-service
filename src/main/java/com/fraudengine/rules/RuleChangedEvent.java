package com.fraudengine.rules;

/**
 * Published when a rule is written, and consumed after the transaction commits.
 *
 * <p>Published from the repository rather than the service because that is the one place every
 * write passes through. A caller that reaches the repository directly still invalidates the
 * snapshot, so the cache cannot be left stale by a path someone forgot about.
 */
public record RuleChangedEvent(String code) {}
