package com.fraudengine.domain;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TransactionEventValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void startValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void acceptsAFullyPopulatedEvent() {
        assertThat(violations(valid())).isEmpty();
    }

    @Test
    void acceptsAnEventMissingOnlyOptionalFields() {
        TransactionEvent event = new TransactionEvent(
                UUID.randomUUID(), Instant.parse("2026-08-03T09:00:00Z"), "acct-1", new CardToken("card-1"),
                new BigDecimal("120.00"), "ZAR", "merch-1", null, "5411", "ZA",
                Channel.CARD_PRESENT, null, null, null, null, null);

        assertThat(violations(event)).isEmpty();
        assertThat(event.hasCoordinates()).isFalse();
    }

    @Test
    void rejectsMissingIdempotencyKey() {
        assertThat(violations(withEventId(null))).containsExactly("eventId");
    }

    @Test
    void rejectsCurrencyOtherThanRand() {
        assertThat(violations(withCurrency("USD"))).contains("supportedCurrency");
    }

    @Test
    void rejectsMalformedCurrencyCode() {
        assertThat(violations(withCurrency("zar"))).contains("currency");
    }

    @Test
    void rejectsNonPositiveAmount() {
        assertThat(violations(withAmount(new BigDecimal("0.00")))).containsExactly("amount");
        assertThat(violations(withAmount(new BigDecimal("-1.00")))).containsExactly("amount");
    }

    /**
     * The column is numeric(18,2), so a third place is rounded on write while the rules evaluate
     * the unrounded value and the audit snapshot keeps it. Two records of one transaction that
     * disagree, and nothing warns. Rejected at the boundary instead, as a foreign currency is.
     */
    @Test
    void rejectsAnAmountWithMoreThanTwoDecimalPlaces() {
        assertThat(violations(withAmount(new BigDecimal("120.999")))).containsExactly("amount");
        assertThat(violations(withAmount(new BigDecimal("0.001")))).containsExactly("amount");
    }

    @Test
    void acceptsAmountsAtOrBelowTwoDecimalPlaces() {
        assertThat(violations(withAmount(new BigDecimal("120.99")))).isEmpty();
        assertThat(violations(withAmount(new BigDecimal("120.9")))).isEmpty();
        assertThat(violations(withAmount(new BigDecimal("120")))).isEmpty();
    }

    @Test
    void rejectsMerchantCategoryCodeThatIsNotFourDigits() {
        assertThat(violations(withMcc("541"))).containsExactly("merchantCategoryCode");
    }

    @Test
    void rejectsCountryCodeThatIsNotIsoAlpha2() {
        assertThat(violations(withCountry("ZAF"))).containsExactly("merchantCountry");
    }

    @Test
    void rejectsCoordinatesOutsideTheGlobe() {
        assertThat(violations(withCoordinates(91.0, 18.4))).containsExactly("latitude");
        assertThat(violations(withCoordinates(-33.9, 181.0))).containsExactly("longitude");
    }

    @Test
    void reportsCoordinatesAsPresentOnlyWhenBothHalvesAreSupplied() {
        assertThat(withCoordinates(-33.92, 18.42).hasCoordinates()).isTrue();
        assertThat(withCoordinates(-33.92, null).hasCoordinates()).isFalse();
        assertThat(withCoordinates(null, 18.42).hasCoordinates()).isFalse();
    }

    private Set<String> violations(TransactionEvent event) {
        return validator.validate(event).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    private static TransactionEvent valid() {
        return new TransactionEvent(
                UUID.randomUUID(), Instant.parse("2026-08-03T09:00:00Z"), "acct-1", new CardToken("card-1"),
                new BigDecimal("120.00"), "ZAR", "merch-1", "Corner Shop", "5411", "ZA",
                Channel.CARD_PRESENT, -33.92, 18.42, "device-1", "196.25.1.1", "groceries");
    }

    private static TransactionEvent withEventId(UUID eventId) {
        TransactionEvent v = valid();
        return new TransactionEvent(eventId, v.occurredAt(), v.accountId(), v.cardToken(), v.amount(),
                v.currency(), v.merchantId(), v.merchantName(), v.merchantCategoryCode(),
                v.merchantCountry(), v.channel(), v.latitude(), v.longitude(), v.deviceId(),
                v.ipAddress(), v.category());
    }

    private static TransactionEvent withCurrency(String currency) {
        TransactionEvent v = valid();
        return new TransactionEvent(v.eventId(), v.occurredAt(), v.accountId(), v.cardToken(),
                v.amount(), currency, v.merchantId(), v.merchantName(), v.merchantCategoryCode(),
                v.merchantCountry(), v.channel(), v.latitude(), v.longitude(), v.deviceId(),
                v.ipAddress(), v.category());
    }

    private static TransactionEvent withAmount(BigDecimal amount) {
        TransactionEvent v = valid();
        return new TransactionEvent(v.eventId(), v.occurredAt(), v.accountId(), v.cardToken(),
                amount, v.currency(), v.merchantId(), v.merchantName(), v.merchantCategoryCode(),
                v.merchantCountry(), v.channel(), v.latitude(), v.longitude(), v.deviceId(),
                v.ipAddress(), v.category());
    }

    private static TransactionEvent withMcc(String mcc) {
        TransactionEvent v = valid();
        return new TransactionEvent(v.eventId(), v.occurredAt(), v.accountId(), v.cardToken(),
                v.amount(), v.currency(), v.merchantId(), v.merchantName(), mcc,
                v.merchantCountry(), v.channel(), v.latitude(), v.longitude(), v.deviceId(),
                v.ipAddress(), v.category());
    }

    private static TransactionEvent withCountry(String country) {
        TransactionEvent v = valid();
        return new TransactionEvent(v.eventId(), v.occurredAt(), v.accountId(), v.cardToken(),
                v.amount(), v.currency(), v.merchantId(), v.merchantName(),
                v.merchantCategoryCode(), country, v.channel(), v.latitude(), v.longitude(),
                v.deviceId(), v.ipAddress(), v.category());
    }

    private static TransactionEvent withCoordinates(Double latitude, Double longitude) {
        TransactionEvent v = valid();
        return new TransactionEvent(v.eventId(), v.occurredAt(), v.accountId(), v.cardToken(),
                v.amount(), v.currency(), v.merchantId(), v.merchantName(),
                v.merchantCategoryCode(), v.merchantCountry(), v.channel(), latitude, longitude,
                v.deviceId(), v.ipAddress(), v.category());
    }
}
