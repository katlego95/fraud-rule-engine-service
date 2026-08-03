package com.fraudengine.generator;

import com.fraudengine.domain.CardToken;
import com.fraudengine.domain.Channel;
import com.fraudengine.domain.TransactionEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A deterministic labelled corpus: thirty days of ordinary traffic across fifty accounts, plus
 * four injected fraud sequences each tagged with the typology it represents and the rule that
 * should catch it.
 *
 * <p>Fixed seed throughout, including the event identifiers, so the same corpus is produced on
 * every run and the calibration numbers in the README are reproducible rather than approximate.
 *
 * <p><strong>Why generated rather than downloaded.</strong> The best-known public card fraud
 * dataset anonymises 28 of its 31 features through PCA, leaving nothing a business rule can
 * reference — a rule against an anonymised principal component cannot explain itself. Others sit
 * behind competition registration, which would mean a reviewer must create a third-party account
 * and download hundreds of megabytes before this project runs at all.
 *
 * <p><strong>The limitation, stated.</strong> The fraud sequences here are constructed to exhibit
 * the typologies they are labelled with, so they demonstrate the calibration <em>method</em> and
 * not production-representative accuracy. Synthetic tabular generators are known to preserve the
 * distribution of individual fields while losing sequential and cross-account behavioural
 * structure; these sequences sidestep that only because they are written deliberately rather than
 * learned. Tuning thresholds for production would require real historical data.
 */
@Component
public class SyntheticCorpus {

    private static final long SEED = 20260803L;
    private static final Instant CORPUS_START = Instant.parse("2026-07-01T06:00:00Z");
    private static final int ACCOUNTS = 50;
    private static final int DAYS = 30;

    /** Everyday retail categories. Deliberately excludes the high-risk set R2 watches for. */
    private static final List<String> ORDINARY_MCCS =
            List.of("5411", "5812", "5541", "5912", "5732", "4111", "5651", "7011");

    /**
     * Categories R2 treats as high-risk. Present in the background on purpose: people do
     * legitimately bet and buy foreign currency, and a rule set is only worth measuring against
     * traffic that can actually trip it.
     */
    private static final List<String> HIGH_RISK_MCCS = List.of("7995", "6051", "5967");

    private static final List<String> MERCHANTS = List.of(
            "checkers-claremont", "woolworths-v-and-a", "engen-n1-city", "clicks-cavendish",
            "takealot-online", "uber-za", "cotton-on-canal-walk", "vida-e-caffe-gardens",
            "pnp-rondebosch", "dischem-tygervalley", "kalahari-online", "shell-sea-point");

    public Corpus generate() {
        Random random = new Random(SEED);
        return new Corpus(background(random), List.of(
                cardTesting(random),
                merchantSpread(random),
                accountTakeover(random),
                geoImpossible(random)));
    }

    /**
     * Ordinary traffic — and deliberately not sanitised traffic.
     *
     * <p>An easy mistake here is to generate background that cannot trip any rule, which produces
     * a flawless false positive rate that means nothing. Real legitimate traffic contains large
     * purchases, online purchases, and occasional spend at categories the rule set treats as
     * high-risk, because people do legitimately gamble and buy foreign currency. So a small
     * proportion of this background genuinely does accumulate weight, and a smaller proportion
     * crosses a band. That cost is the number the calibration report exists to make concrete.
     *
     * <p>What it does not contain is sequences dense enough to trip a velocity window, because
     * those are the injected typologies and a background collision would make the labels
     * ambiguous.
     */
    private List<TransactionEvent> background(Random random) {
        List<TransactionEvent> events = new ArrayList<>();

        for (int account = 0; account < ACCOUNTS; account++) {
            String accountId = "acct-%03d".formatted(account);
            CardToken card = new CardToken("4000%04d%08d".formatted(account, 10_000_000 + account * 137));
            int transactions = 18 + random.nextInt(15);

            for (int i = 0; i < transactions; i++) {
                Instant at = CORPUS_START.plus(Duration.ofMinutes(random.nextInt(DAYS * 24 * 60)));
                boolean online = random.nextInt(100) < 30;
                boolean highRiskCategory = random.nextInt(100) < 3;

                // Occasional large card-present purchases — a laptop, a flight — sit above the
                // high-amount threshold on their own without being fraud.
                BigDecimal amount = online
                        ? money(random, 50, 8_500)
                        : (random.nextInt(100) < 6 ? money(random, 10_000, 16_000) : money(random, 40, 6_000));

                events.add(new TransactionEvent(
                        deterministicId("bg", account, i),
                        at,
                        accountId,
                        card,
                        amount,
                        "ZAR",
                        MERCHANTS.get(random.nextInt(MERCHANTS.size())),
                        null,
                        highRiskCategory
                                ? HIGH_RISK_MCCS.get(random.nextInt(HIGH_RISK_MCCS.size()))
                                : ORDINARY_MCCS.get(random.nextInt(ORDINARY_MCCS.size())),
                        "ZA",
                        online ? Channel.ECOMMERCE : Channel.CARD_PRESENT,
                        null, null, null, null, "retail"));
            }
        }
        return List.copyOf(events);
    }

    /**
     * Five authorisations on one card inside four minutes at a single merchant. Kept to one
     * merchant deliberately: spreading them would also trip the merchant-spread rule, and a
     * scenario that trips two rules cannot show which one caught it.
     */
    private LabelledScenario cardTesting(Random random) {
        String accountId = "acct-fraud-card-testing";
        CardToken card = new CardToken("4000999900001111");
        Instant start = CORPUS_START.plus(Duration.ofDays(11)).plus(Duration.ofHours(2));

        List<TransactionEvent> events = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            events.add(new TransactionEvent(
                    deterministicId("card-testing", 0, i),
                    start.plus(Duration.ofSeconds(45L * i)),
                    accountId, card,
                    money(random, 12, 95),
                    "ZAR", "quicksilver-digital", null, "5732", "ZA",
                    Channel.ECOMMERCE, null, null, null, null, "digital"));
        }

        return new LabelledScenario("card-testing", "Card testing", "CARD_TXN_VELOCITY",
                "Five small authorisations on one card in under four minutes, probing which stolen "
                        + "numbers are still live.",
                List.copyOf(events));
    }

    /**
     * Four distinct merchants on one card inside eight minutes. Spaced so that no five-minute
     * window contains more than four transactions, which keeps the card-count rule out of it.
     */
    private LabelledScenario merchantSpread(Random random) {
        String accountId = "acct-fraud-merchant-spread";
        CardToken card = new CardToken("4000999900002222");
        Instant start = CORPUS_START.plus(Duration.ofDays(17)).plus(Duration.ofHours(9));

        List<String> merchants = List.of("shop-alpha", "shop-beta", "shop-gamma", "shop-delta");
        List<TransactionEvent> events = new ArrayList<>();
        for (int i = 0; i < merchants.size(); i++) {
            events.add(new TransactionEvent(
                    deterministicId("merchant-spread", 0, i),
                    start.plus(Duration.ofMinutes(2L * i)),
                    accountId, card,
                    money(random, 80, 400),
                    "ZAR", merchants.get(i), null, "5411", "ZA",
                    Channel.CARD_PRESENT, null, null, null, null, "retail"));
        }

        return new LabelledScenario("merchant-spread", "Card testing across merchants",
                "MERCHANT_SPREAD_VELOCITY",
                "One card used at four different merchants within eight minutes, spreading activity "
                        + "to stay under any single merchant's limit.",
                List.copyOf(events));
    }

    /**
     * Six withdrawals of R9,000 over eighteen hours: R54,000 against a R50,000 daily ceiling.
     * Each amount sits below the high-amount threshold on purpose — the point of the typology is
     * that no individual transaction looks unusual and only the aggregate does.
     */
    private LabelledScenario accountTakeover(Random random) {
        String accountId = "acct-fraud-takeover";
        CardToken card = new CardToken("4000999900003333");
        Instant start = CORPUS_START.plus(Duration.ofDays(22)).plus(Duration.ofHours(1));

        List<TransactionEvent> events = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            events.add(new TransactionEvent(
                    deterministicId("account-takeover", 0, i),
                    start.plus(Duration.ofHours(3L * i)),
                    accountId, card,
                    new BigDecimal("9000.00"),
                    "ZAR", "atm-" + (i % 3), null, "6011", "ZA",
                    Channel.ATM, null, null, null, null, "cash"));
        }

        return new LabelledScenario("account-takeover", "Account takeover and cash-out",
                "ACCOUNT_AMOUNT_VELOCITY",
                "Six ATM withdrawals of R9,000 over eighteen hours. No single amount is remarkable; "
                        + "the R54,000 total against a R50,000 daily ceiling is.",
                List.copyOf(events));
    }

    /** Cape Town then Johannesburg fifty minutes later — about 1,265 km, roughly 1,500 km/h. */
    private LabelledScenario geoImpossible(Random random) {
        String accountId = "acct-fraud-geo";
        CardToken card = new CardToken("4000999900004444");
        Instant start = CORPUS_START.plus(Duration.ofDays(26)).plus(Duration.ofHours(14));

        TransactionEvent capeTown = new TransactionEvent(
                deterministicId("geo-impossible", 0, 0), start, accountId, card,
                money(random, 150, 600), "ZAR", "cape-town-waterfront", null, "5812", "ZA",
                Channel.CARD_PRESENT, -33.9049, 18.4207, null, null, "dining");

        TransactionEvent johannesburg = new TransactionEvent(
                deterministicId("geo-impossible", 0, 1), start.plus(Duration.ofMinutes(50)),
                accountId, card, money(random, 150, 600), "ZAR", "sandton-city", null, "5812", "ZA",
                Channel.CARD_PRESENT, -26.1076, 28.0567, null, null, "dining");

        return new LabelledScenario("geo-impossible", "Cloned card used in a second location",
                "GEO_IMPOSSIBLE",
                "The same card used in Cape Town and Johannesburg fifty minutes apart — about "
                        + "1,265 km, which no passenger aircraft covers in the time.",
                List.of(capeTown, johannesburg));
    }

    private static BigDecimal money(Random random, int minRand, int maxRand) {
        int cents = (minRand * 100) + random.nextInt((maxRand - minRand) * 100);
        return BigDecimal.valueOf(cents, 2).setScale(2, RoundingMode.UNNECESSARY);
    }

    /** Stable identifiers so a re-run seeds the same events and idempotency makes it a no-op. */
    private static UUID deterministicId(String kind, int group, int index) {
        return UUID.nameUUIDFromBytes("%s:%d:%d:%d".formatted(kind, group, index, SEED)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
