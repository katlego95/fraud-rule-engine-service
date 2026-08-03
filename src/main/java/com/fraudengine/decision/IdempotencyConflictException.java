package com.fraudengine.decision;

import java.util.UUID;

/**
 * The same event identifier arrived carrying a different body. Returning the stored decision
 * would answer a question the caller did not ask, and evaluating the new body would break the
 * one-decision-per-event guarantee, so neither is safe.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(UUID eventId) {
        super("Event %s was already submitted with a different payload".formatted(eventId));
    }
}
