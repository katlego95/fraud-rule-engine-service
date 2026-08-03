package com.fraudengine.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A tokenised card reference. Never a PAN — tokenisation happens upstream, so card numbers do not
 * enter this service at all.
 *
 * <p>The token renders masked wherever it is serialised or printed. Masking by convention — "mask
 * it when you log it" — fails the first time someone adds a log line in a hurry, so the unmasked
 * value is reachable only through {@link #value()}, which is used by the queries and the
 * persistence layer and nowhere else.
 */
public record CardToken(String value) {

    private static final int VISIBLE_SUFFIX = 4;

    public CardToken {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Card token must not be blank");
        }
    }

    @JsonCreator
    public static CardToken of(String value) {
        return new CardToken(value);
    }

    @JsonValue
    public String masked() {
        if (value.length() <= VISIBLE_SUFFIX) {
            return "tok_****";
        }
        return "tok_****" + value.substring(value.length() - VISIBLE_SUFFIX);
    }

    @Override
    public String toString() {
        return masked();
    }
}
