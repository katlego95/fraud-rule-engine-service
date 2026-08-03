package com.fraudengine.decision;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Position in the {@code (evaluated_at desc, decision_id desc)} ordering. Opaque to the client so
 * the sort key can change without breaking callers who stored one.
 */
public record Cursor(Instant evaluatedAt, UUID decisionId) {

    private static final String SEPARATOR = "|";

    public String encode() {
        String raw = evaluatedAt + SEPARATOR + decisionId;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String encoded) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf(SEPARATOR);
            return new Cursor(
                    Instant.parse(raw.substring(0, separator)),
                    UUID.fromString(raw.substring(separator + 1)));
        } catch (RuntimeException e) {
            throw new InvalidCursorException(encoded, e);
        }
    }
}
