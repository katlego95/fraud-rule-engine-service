package com.fraudengine.generator;

import com.fraudengine.decision.DecisionService;
import com.fraudengine.domain.TransactionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Loads the labelled corpus on startup under the demo profile.
 *
 * <p>Events are put through the real decision path rather than inserted directly. Raw inserts
 * would be faster but would leave thousands of transactions that are "already seen" with no
 * decision behind them, breaking the invariant idempotency depends on and making
 * {@code GET /transactions/{eventId}/decision} return 404 for most of the seeded data. Seeded
 * history that cannot be queried the way live history is queried is not history worth seeding.
 *
 * <p>Identifiers are deterministic, so restarting an already-seeded database replays rather than
 * duplicating.
 */
@Component
@Profile("demo")
class CorpusSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CorpusSeeder.class);

    private final SyntheticCorpus corpus;
    private final DecisionService decisions;

    CorpusSeeder(SyntheticCorpus corpus, DecisionService decisions) {
        this.corpus = corpus;
        this.decisions = decisions;
    }

    @Override
    public void run(ApplicationArguments args) {
        Corpus generated = corpus.generate();
        long start = System.currentTimeMillis();

        int replayed = 0;
        for (TransactionEvent event : generated.allEvents()) {
            if (decisions.decide(event).replayed()) {
                replayed++;
            }
        }

        if (replayed > 0) {
            log.info("Corpus already seeded: {} of {} events replayed", replayed,
                    generated.allEvents().size());
        } else {
            log.info("Seeded {} legitimate transactions and {} labelled fraud scenarios in {} ms",
                    generated.background().size(), generated.scenarios().size(),
                    System.currentTimeMillis() - start);
        }

        log.info("Try: GET /api/v1/decisions?verdict=BLOCK,REVIEW");
        generated.scenarios().forEach(scenario -> log.info(
                "  scenario '{}' — {} on account {} (expects {})",
                scenario.name(), scenario.typology(),
                scenario.triggeringEvent().accountId(), scenario.expectedRuleCode()));
    }
}
