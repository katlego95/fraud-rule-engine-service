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
 * <p>Device and IP identifiers are hashed before they are stored, so the stored values are
 * unreadable to anyone who obtains the table. The hash is deterministic: the same device always
 * hashes to the same value, so equality still holds and rules can group and count by identifier
 * without the plaintext ever being persisted. What is lost is structure — a hash cannot be matched
 * by IP subnet or prefix, only by exact equality.
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
                fingerprint(event.deviceId()), fingerprint(event.ipAddress()), event.category());
    }

    /**
     * The one place an identifier is turned into its stored form. Public because evaluators that
     * query by device or IP have the plaintext in hand and the table holds only hashes: they must
     * fingerprint the value before looking it up, using this salt and this algorithm. A second
     * implementation elsewhere would produce a different digest, match nothing, and report zero —
     * a rule that never fires and never errors.
     */
    public String fingerprint(String value) {
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
