package com.fraudengine.rules;

import com.fraudengine.domain.CardToken;
import com.fraudengine.domain.Channel;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Windowed reads over persisted events, bounded by event time rather than the server clock.
 *
 * <p>Every window is closed at both ends and inclusive of its upper bound, which is the event
 * being evaluated: the pipeline persists an event before evaluating it, so the transaction under
 * evaluation is already inside its own window and counts towards its own threshold. Each rule's
 * description says so, because leaving it implicit shifts every threshold by one.
 */
@Repository
public class VelocityRepository {

    /** A position fix from a prior transaction on the same card. */
    public record Position(UUID eventId, Instant occurredAt, double latitude, double longitude,
            String merchantId, Channel channel) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    VelocityRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public long countCardTransactions(CardToken cardToken, Instant from, Instant to) {
        return jdbc.sql("""
                select count(*) from transaction_events
                where card_token = :cardToken and occurred_at between :from and :to
                """)
                .param("cardToken", cardToken.value())
                .param("from", at(from))
                .param("to", at(to))
                .query(Long.class)
                .single();
    }

    public long countDistinctMerchants(CardToken cardToken, Instant from, Instant to) {
        return jdbc.sql("""
                select count(distinct merchant_id) from transaction_events
                where card_token = :cardToken and occurred_at between :from and :to
                """)
                .param("cardToken", cardToken.value())
                .param("from", at(from))
                .param("to", at(to))
                .query(Long.class)
                .single();
    }

    public BigDecimal sumAccountAmount(String accountId, Instant from, Instant to) {
        return jdbc.sql("""
                select coalesce(sum(amount), 0) from transaction_events
                where account_id = :accountId and occurred_at between :from and :to
                """)
                .param("accountId", accountId)
                .param("from", at(from))
                .param("to", at(to))
                .query(BigDecimal.class)
                .single();
    }

     // How many distinct cards have been seen from this IP in the window, including the current event's card
    public long countDistinctCardsForIp(String ipFingerprint, Instant from, Instant to) {
        return jdbc.sql("""
                select count(distinct card_token) from transaction_events
                where ip_fingerprint = :ipFingerprint and card_token = :cardToken and occurred_at between :from and :to
                """)
                .param("ipFingerprint", ipFingerprint)
                .param("from", at(from))
                .param("to", at(to))
                .query(Long.class)
                .single();
    }

    // how many distinct accounts have been seen from this device in the window, including the current event's account
    public long countDistinctAccountsForDevice(String deviceFingerprint, Instant from, Instant to) {
        return jdbc.sql("""
                select count(distinct account_id) from transaction_events
                where device_fingerprint = :deviceFingerprint and occurred_at between :from and :to
                """)
                .param("deviceFingerprint", deviceFingerprint)
                .param("from", at(from))
                .param("to", at(to))
                .query(Long.class)
                .single();
    }


    /**
     * Positions on this card within the window, excluding the event being evaluated. The
     * exclusion matters: without it the current event pairs with itself at zero distance and zero
     * elapsed time, which is the division by zero the geo rule would otherwise have to special-case.
     */
    public List<Position> positionsExcluding(CardToken cardToken, Instant from, Instant to, UUID excluded) {
        return jdbc.sql("""
                select event_id, occurred_at, latitude, longitude, merchant_id, channel
                from transaction_events
                where card_token = :cardToken
                  and occurred_at between :from and :to
                  and event_id <> :excluded
                  and latitude is not null and longitude is not null
                order by occurred_at desc
                """)
                .param("cardToken", cardToken.value())
                .param("from", at(from))
                .param("to", at(to))
                .param("excluded", excluded)
                .query((rs, rowNum) -> new Position(
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getDouble("latitude"),
                        rs.getDouble("longitude"),
                        rs.getString("merchant_id"),
                        Channel.valueOf(rs.getString("channel"))))
                .list();
    }

    private OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, clock.getZone());
    }
}
