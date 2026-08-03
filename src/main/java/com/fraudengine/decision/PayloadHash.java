package com.fraudengine.decision;

import com.fraudengine.domain.TransactionEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fingerprints a submitted event so a replay carrying a different body can be told apart from a
 * genuine retry. Hashed rather than compared field by field because the comparison has to survive
 * every field the contract grows later.
 */
@Component
class PayloadHash {

    private final JsonMapper json;

    PayloadHash(JsonMapper json) {
        this.json = json;
    }

    String of(TransactionEvent event) {
        byte[] canonical = json.writeValueAsBytes(event);
        return HexFormat.of().formatHex(sha256().digest(canonical));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
