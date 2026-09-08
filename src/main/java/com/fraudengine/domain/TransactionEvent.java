package com.fraudengine.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The input contract: one already-categorised card transaction. Field set follows the Sparkov
 * synthetic card fraud dataset schema, which was built to resemble real card transaction
 * structure.
 *
 * <p>Required fields are rejected when missing. Optional fields are accepted when missing, and
 * any rule that needed one is recorded as {@link OutcomeStatus#NOT_EVALUABLE} rather than as a
 * non-match.
 *
 * @param eventId client-supplied idempotency key; resubmitting one returns the original decision
 * @param occurredAt event time, which drives every velocity window — never the server clock
 * @param amount minor-unit-safe decimal; serialised across the API boundary as a string
 */
public record TransactionEvent(
        @NotNull UUID eventId,
        @NotNull Instant occurredAt,
        @NotBlank String accountId,
        @NotNull CardToken cardToken,
        @NotNull @Positive BigDecimal amount,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO 4217 alphabetic code")
        String currency,
        @NotBlank String merchantId,
        String merchantName,
        @NotBlank @Pattern(regexp = "^[0-9]{4}$", message = "must be a four-digit MCC")
        String merchantCategoryCode,
        @NotBlank @Pattern(regexp = "^[A-Z]{2}$", message = "must be an ISO 3166 alpha-2 code")
        String merchantCountry,
        @NotNull Channel channel,
        @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
        String deviceId,
        String ipAddress,
        String category) {

    /**
     * Amount thresholds and the account velocity total are denominated in rand. Summing mixed
     * currencies would make R7 meaningless, and converting them would require an FX rate source
     * this service does not have, so mixed-currency traffic is rejected at the boundary rather
     * than silently mis-scored.
     */
    public static final String SUPPORTED_CURRENCY = "ZAR";

    @JsonIgnore
    @AssertTrue(message = "only " + SUPPORTED_CURRENCY + " transactions are supported")
    public boolean isSupportedCurrency() {
        return currency == null || SUPPORTED_CURRENCY.equals(currency);
    }

    /** Geo-velocity needs both halves of a coordinate pair; one without the other is unusable. */
    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }

    public boolean hasIpAddress() {
        return ipAddress != null && !ipAddress.isBlank();
    }

    public boolean hasDeviceId() {
        return deviceId != null && !deviceId.isBlank();
    }
}
