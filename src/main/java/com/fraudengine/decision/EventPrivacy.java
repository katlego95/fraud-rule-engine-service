package com.fraudengine.decision;

import com.fraudengine.domain.TransactionEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reduces what a database compromise yields.
 *
 * <p>Device and IP identifiers are hashed before they are stored: nothing queries them and no rule
 * reads them, so a deterministic one-way hash costs nothing operationally while making the stored
 * values unreadable. The same device still hashes to the same value, so they remain usable for
 * correlation.
 *
 * <p>Card token and account identifier are deliberately left in plaintext — velocity windows query
 * by card token and the retrieval API filters on both, so hashing them would break the queries
 * this service exists to run. That is an accepted residual risk, recorded in ADR 0005 rather than
 * left unsaid.
 */
@Component
public class EventPrivacy {

    private final String salt;

    EventPrivacy(@Value("${fraud.privacy.hash-salt}") String salt) {
        this.salt = salt;
    }

    public TransactionEvent forStorage(TransactionEvent event) {
        return new TransactionEvent(
                event.eventId(), event.occurredAt(), event.accountId(), event.cardToken(),
                event.amount(), event.currency(), event.merchantId(), event.merchantName(),
                event.merchantCategoryCode(), event.merchantCountry(), event.channel(),
                event.latitude(), event.longitude(),
                hash(event.deviceId()), hash(event.ipAddress()), event.category());
    }

    private String hash(String value) {
        if (value == null) {
            return null;
        }
        byte[] salted = (salt + value).getBytes(StandardCharsets.UTF_8);
        return HexFormat.of().formatHex(sha256().digest(salted));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
