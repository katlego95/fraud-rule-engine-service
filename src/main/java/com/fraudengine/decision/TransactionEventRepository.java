package com.fraudengine.decision;

import com.fraudengine.domain.TransactionEvent;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionEventRepository {

    private final JdbcClient jdbc;
    private final Clock clock;

    TransactionEventRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Inserts the event, or does nothing if that event identifier is already present.
     *
     * <p>{@code on conflict do nothing} rather than a preceding existence check: two concurrent
     * requests carrying the same identifier both pass a check, but only one wins an insert. The
     * loser blocks on the winner's uncommitted row, then sees zero rows affected once the winner
     * commits — by which point the winner's decision is committed too, because both are written
     * in the same transaction.
     *
     * @return true if this call inserted the event, false if it was already present
     */
    public boolean insertIfAbsent(TransactionEvent event, String payloadHash) {
        int inserted = jdbc.sql("""
                insert into transaction_events (
                    event_id, occurred_at, account_id, card_token, amount, currency, merchant_id,
                    merchant_name, merchant_category_code, merchant_country, channel, latitude,
                    longitude, device_id, ip_address, category, payload_hash, received_at)
                values (:eventId, :occurredAt, :accountId, :cardToken, :amount, :currency, :merchantId,
                        :merchantName, :merchantCategoryCode, :merchantCountry, :channel, :latitude,
                        :longitude, :deviceId, :ipAddress, :category, :payloadHash, :receivedAt)
                on conflict (event_id) do nothing
                """)
                .param("eventId", event.eventId())
                .param("occurredAt", OffsetDateTime.ofInstant(event.occurredAt(), clock.getZone()))
                .param("accountId", event.accountId())
                .param("cardToken", event.cardToken().value())
                .param("amount", event.amount())
                .param("currency", event.currency())
                .param("merchantId", event.merchantId())
                .param("merchantName", event.merchantName())
                .param("merchantCategoryCode", event.merchantCategoryCode())
                .param("merchantCountry", event.merchantCountry())
                .param("channel", event.channel().name())
                .param("latitude", event.latitude())
                .param("longitude", event.longitude())
                .param("deviceId", event.deviceId())
                .param("ipAddress", event.ipAddress())
                .param("category", event.category())
                .param("payloadHash", payloadHash)
                .param("receivedAt", OffsetDateTime.now(clock))
                .update();

        return inserted == 1;
    }

    public Optional<String> payloadHashOf(UUID eventId) {
        return jdbc.sql("select payload_hash from transaction_events where event_id = :eventId")
                .param("eventId", eventId)
                .query(String.class)
                .optional();
    }
}
