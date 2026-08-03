package com.fraudengine.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.fraudengine.PostgresIntegrationTest;
import com.fraudengine.domain.CardToken;
import com.fraudengine.domain.Channel;
import com.fraudengine.domain.TransactionEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Deliberately not transactional: the property under test is what happens across concurrent
 * committed transactions, which a rolled-back test transaction cannot express.
 */
class IdempotencyConcurrencyIT extends PostgresIntegrationTest {

    @Autowired
    private DecisionService service;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void clearCommittedRows() {
        jdbc.sql("delete from decision_rule_outcomes").update();
        jdbc.sql("delete from decisions").update();
        jdbc.sql("delete from transaction_events").update();
    }

    @Test
    void concurrentSubmissionsOfOneEventProduceExactlyOneDecision() throws Exception {
        TransactionEvent event = event();
        int racers = 8;

        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch startLine = new CountDownLatch(1);
        List<Callable<DecisionResult>> submissions = java.util.stream.IntStream.range(0, racers)
                .<Callable<DecisionResult>>mapToObj(i -> () -> {
                    startLine.await();
                    return service.decide(event);
                })
                .toList();

        List<Future<DecisionResult>> futures = submissions.stream().map(pool::submit).toList();
        startLine.countDown();

        List<DecisionResult> results = new java.util.ArrayList<>();
        for (Future<DecisionResult> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(results).hasSize(racers);
        assertThat(results).extracting(result -> result.decision().decisionId()).containsOnly(
                results.getFirst().decision().decisionId());
        assertThat(results.stream().filter(result -> !result.replayed()).count())
                .as("exactly one submission evaluates; the rest replay")
                .isEqualTo(1);

        assertThat(countOf("decisions")).isEqualTo(1);
        assertThat(countOf("transaction_events")).isEqualTo(1);
    }

    private long countOf(String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }

    private static TransactionEvent event() {
        return new TransactionEvent(UUID.randomUUID(), Instant.parse("2026-08-03T09:00:00Z"),
                "acct-race", new CardToken("card-race"), new BigDecimal("120.00"), "ZAR", "merch-1", "Merchant",
                "5411", "ZA", Channel.CARD_PRESENT, null, null, null, null, "groceries");
    }
}
