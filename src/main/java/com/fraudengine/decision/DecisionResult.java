package com.fraudengine.decision;

import com.fraudengine.domain.Decision;

/**
 * @param replayed true when this decision was returned from an earlier submission of the same
 *     event identifier rather than evaluated now
 */
public record DecisionResult(Decision decision, boolean replayed) {}
