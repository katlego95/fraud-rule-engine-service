package com.fraudengine.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.fraudengine.PostgresIntegrationTest;
import com.fraudengine.decision.DecisionService;
import com.fraudengine.domain.CardToken;
import com.fraudengine.domain.Channel;
import com.fraudengine.domain.Decision;
import com.fraudengine.domain.OutcomeStatus;
import com.fraudengine.domain.RuleOutcome;
import com.fraudengine.domain.TransactionEvent;
import com.fraudengine.domain.Verdict;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class GeoSpeedEvaluatorIT extends PostgresIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-08-03T09:00:00Z");
    private static final double CAPE_TOWN_LAT = -33.9249;
    private static final double CAPE_TOWN_LON = 18.4241;
    private static final double JOHANNESBURG_LAT = -26.2041;
    private static final double JOHANNESBURG_LON = 28.0473;

    @Autowired
    private DecisionService service;

    @Test
    void capeTownToJohannesburgInOneHourIsImpossible() {
        decide("card-geo-1", T0, CAPE_TOWN_LAT, CAPE_TOWN_LON, Channel.CARD_PRESENT, "m1");
        Decision second = decide("card-geo-1", T0.plus(Duration.ofHours(1)),
                JOHANNESBURG_LAT, JOHANNESBURG_LON, Channel.CARD_PRESENT, "m2");

        RuleOutcome geo = outcome(second, "GEO_IMPOSSIBLE");
        assertThat(geo.status()).isEqualTo(OutcomeStatus.MATCHED);
        assertThat(geo.reason()).contains("km/h");
        assertThat(second.verdict()).isEqualTo(Verdict.BLOCK);
    }

    @Test
    void theSameJourneyOverThreeHoursIsAnAeroplane() {
        decide("card-geo-2", T0, CAPE_TOWN_LAT, CAPE_TOWN_LON, Channel.CARD_PRESENT, "m1");
        Decision second = decide("card-geo-2", T0.plus(Duration.ofHours(3)),
                JOHANNESBURG_LAT, JOHANNESBURG_LON, Channel.CARD_PRESENT, "m2");

        assertThat(outcome(second, "GEO_IMPOSSIBLE").status()).isEqualTo(OutcomeStatus.NOT_MATCHED);
    }

    @Test
    void missingCoordinatesAreUnevaluableRatherThanANonMatch() {
        Decision decision = decide("card-geo-3", T0, null, null, Channel.CARD_PRESENT, "m1");

        RuleOutcome geo = outcome(decision, "GEO_IMPOSSIBLE");
        assertThat(geo.status()).isEqualTo(OutcomeStatus.NOT_EVALUABLE);
        assertThat(geo.reason()).contains("No coordinates");
    }

    @Test
    void firstTransactionOnACardHasNothingToCompareAgainst() {
        Decision decision = decide("card-geo-4", T0, CAPE_TOWN_LAT, CAPE_TOWN_LON,
                Channel.CARD_PRESENT, "m1");

        assertThat(outcome(decision, "GEO_IMPOSSIBLE").status()).isEqualTo(OutcomeStatus.NOT_EVALUABLE);
    }

    @Test
    void ecommerceIsNotPairedBecauseItsLocationIsAnIpAddressNotAPerson() {
        decide("card-geo-5", T0, CAPE_TOWN_LAT, CAPE_TOWN_LON, Channel.CARD_PRESENT, "m1");
        Decision online = decide("card-geo-5", T0.plus(Duration.ofMinutes(1)),
                JOHANNESBURG_LAT, JOHANNESBURG_LON, Channel.ECOMMERCE, "m2");

        RuleOutcome geo = outcome(online, "GEO_IMPOSSIBLE");
        assertThat(geo.status()).isEqualTo(OutcomeStatus.NOT_MATCHED);
        assertThat(geo.reason()).contains("does not place the cardholder anywhere");
    }

    @Test
    void neighbouringMerchantsSecondsApartAreBelowTheDistanceFloor() {
        // ~200 m apart: coordinate jitter, not travel. Without the floor this implies supersonic
        // speed over a few seconds and would block a customer walking between two shops.
        decide("card-geo-6", T0, CAPE_TOWN_LAT, CAPE_TOWN_LON, Channel.CARD_PRESENT, "m1");
        Decision nearby = decide("card-geo-6", T0.plus(Duration.ofSeconds(20)),
                CAPE_TOWN_LAT + 0.0018, CAPE_TOWN_LON, Channel.CARD_PRESENT, "m2");

        RuleOutcome geo = outcome(nearby, "GEO_IMPOSSIBLE");
        assertThat(geo.status()).isEqualTo(OutcomeStatus.NOT_MATCHED);
        assertThat(geo.reason()).contains("no travel to assess");
    }

    @Test
    void twoPlacesAtTheSameInstantMatchesWithoutDividingByZero() {
        decide("card-geo-7", T0, CAPE_TOWN_LAT, CAPE_TOWN_LON, Channel.CARD_PRESENT, "m1");
        Decision simultaneous = decide("card-geo-7", T0,
                JOHANNESBURG_LAT, JOHANNESBURG_LON, Channel.CARD_PRESENT, "m2");

        RuleOutcome geo = outcome(simultaneous, "GEO_IMPOSSIBLE");
        assertThat(geo.status()).isEqualTo(OutcomeStatus.MATCHED);
        assertThat(geo.reason()).contains("same instant");
    }

    private Decision decide(String card, Instant at, Double latitude, Double longitude,
            Channel channel, String merchant) {
        return service.decide(new TransactionEvent(UUID.randomUUID(), at, "acct-" + card,
                new CardToken(card), new BigDecimal("100.00"), "ZAR", merchant, "Merchant", "5411",
                "ZA", channel, latitude, longitude, null, null, "groceries")).decision();
    }

    private static RuleOutcome outcome(Decision decision, String code) {
        return decision.outcomes().stream()
                .filter(o -> o.ruleCode().equals(code))
                .findFirst()
                .orElseThrow();
    }
}
