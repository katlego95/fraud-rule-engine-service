package com.fraudengine.decision;

import com.fraudengine.domain.CardToken;
import com.fraudengine.domain.Decision;
import com.fraudengine.domain.OutcomeStatus;
import com.fraudengine.domain.RuleMode;
import com.fraudengine.domain.RuleNature;
import com.fraudengine.domain.RuleOutcome;
import com.fraudengine.domain.Verdict;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DecisionRepository {

    private static final String DECISION_COLUMNS = """
            decision_id, event_id, account_id, card_token, occurred_at, evaluated_at, verdict,
            decisive_verdict, score_verdict, total_score, review_from, block_from, engine_version,
            event_snapshot::text as event_snapshot
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    DecisionRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void insert(Decision decision) {
        jdbc.sql("""
                insert into decisions (
                    decision_id, event_id, account_id, card_token, occurred_at, evaluated_at,
                    verdict, decisive_verdict, score_verdict, total_score, review_from, block_from,
                    engine_version, event_snapshot)
                values (:decisionId, :eventId, :accountId, :cardToken, :occurredAt, :evaluatedAt,
                        :verdict, :decisiveVerdict, :scoreVerdict, :totalScore, :reviewFrom,
                        :blockFrom, :engineVersion, cast(:eventSnapshot as jsonb))
                """)
                .param("decisionId", decision.decisionId())
                .param("eventId", decision.eventId())
                .param("accountId", decision.accountId())
                .param("cardToken", decision.cardToken().value())
                .param("occurredAt", OffsetDateTime.ofInstant(decision.occurredAt(), clock.getZone()))
                .param("evaluatedAt", OffsetDateTime.ofInstant(decision.evaluatedAt(), clock.getZone()))
                .param("verdict", decision.verdict().name())
                .param("decisiveVerdict", decision.decisiveVerdict().name())
                .param("scoreVerdict", decision.scoreVerdict().name())
                .param("totalScore", decision.totalScore())
                .param("reviewFrom", decision.reviewFrom())
                .param("blockFrom", decision.blockFrom())
                .param("engineVersion", decision.engineVersion())
                .param("eventSnapshot", decision.eventSnapshot())
                .update();

        for (RuleOutcome outcome : decision.outcomes()) {
            jdbc.sql("""
                    insert into decision_rule_outcomes (
                        id, decision_id, rule_id, rule_code, rule_version, mode, nature, status,
                        verdict, contribution, reason)
                    values (:id, :decisionId, :ruleId, :ruleCode, :ruleVersion, :mode, :nature,
                            :status, :verdict, :contribution, :reason)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("decisionId", decision.decisionId())
                    .param("ruleId", outcome.ruleId())
                    .param("ruleCode", outcome.ruleCode())
                    .param("ruleVersion", outcome.ruleVersion())
                    .param("mode", outcome.mode().name())
                    .param("nature", outcome.nature().name())
                    .param("status", outcome.status().name())
                    .param("verdict", outcome.verdict() == null ? null : outcome.verdict().name())
                    .param("contribution", outcome.contribution())
                    .param("reason", outcome.reason())
                    .update();
        }
    }

    public Optional<Decision> findById(UUID decisionId) {
        return jdbc.sql("select " + DECISION_COLUMNS + " from decisions where decision_id = :id")
                .param("id", decisionId)
                .query(decisionMapper())
                .optional()
                .map(this::withOutcomes);
    }

    public Optional<Decision> findByEventId(UUID eventId) {
        return jdbc.sql("select " + DECISION_COLUMNS + " from decisions where event_id = :eventId")
                .param("eventId", eventId)
                .query(decisionMapper())
                .optional()
                .map(this::withOutcomes);
    }

    /**
     * Keyset page over {@code (evaluated_at desc, decision_id desc)}. Offset pagination degrades
     * linearly and silently skips or repeats rows when data is inserted mid-scan, which in a
     * service receiving continuous events is always.
     */
    public List<Decision> search(DecisionQuery query, int limit) {
        StringBuilder sql = new StringBuilder("select " + DECISION_COLUMNS + " from decisions where 1 = 1");

        if (query.accountId() != null) {
            sql.append(" and account_id = :accountId");
        }
        if (query.cardToken() != null) {
            sql.append(" and card_token = :cardToken");
        }
        if (!query.verdicts().isEmpty()) {
            sql.append(" and verdict in (:verdicts)");
        }
        if (query.minScore() != null) {
            sql.append(" and total_score >= :minScore");
        }
        if (query.from() != null) {
            sql.append(" and evaluated_at >= :from");
        }
        if (query.to() != null) {
            sql.append(" and evaluated_at < :to");
        }
        if (query.ruleCode() != null) {
            sql.append(" and exists (select 1 from decision_rule_outcomes o"
                    + " where o.decision_id = decisions.decision_id"
                    + " and o.rule_code = :ruleCode and o.status = 'MATCHED')");
        }
        if (query.cursor() != null) {
            sql.append(" and (evaluated_at, decision_id) < (:cursorEvaluatedAt, :cursorDecisionId)");
        }
        sql.append(" order by evaluated_at desc, decision_id desc limit :limit");

        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("limit", limit);

        if (query.accountId() != null) {
            spec = spec.param("accountId", query.accountId());
        }
        if (query.cardToken() != null) {
            spec = spec.param("cardToken", query.cardToken());
        }
        if (!query.verdicts().isEmpty()) {
            spec = spec.param("verdicts", query.verdicts().stream().map(Verdict::name).toList());
        }
        if (query.minScore() != null) {
            spec = spec.param("minScore", query.minScore());
        }
        if (query.from() != null) {
            spec = spec.param("from", OffsetDateTime.ofInstant(query.from(), clock.getZone()));
        }
        if (query.to() != null) {
            spec = spec.param("to", OffsetDateTime.ofInstant(query.to(), clock.getZone()));
        }
        if (query.ruleCode() != null) {
            spec = spec.param("ruleCode", query.ruleCode());
        }
        if (query.cursor() != null) {
            spec = spec.param("cursorEvaluatedAt",
                            OffsetDateTime.ofInstant(query.cursor().evaluatedAt(), clock.getZone()))
                    .param("cursorDecisionId", query.cursor().decisionId());
        }

        return spec.query(decisionMapper()).list();
    }

    private Decision withOutcomes(Decision decision) {
        List<RuleOutcome> outcomes = jdbc.sql("""
                select rule_id, rule_code, rule_version, mode, nature, status, verdict,
                       contribution, reason
                from decision_rule_outcomes where decision_id = :decisionId order by rule_code
                """)
                .param("decisionId", decision.decisionId())
                .query(outcomeMapper())
                .list();

        return new Decision(decision.decisionId(), decision.eventId(), decision.accountId(),
                decision.cardToken(), decision.occurredAt(), decision.evaluatedAt(),
                decision.verdict(), decision.decisiveVerdict(), decision.scoreVerdict(),
                decision.totalScore(), decision.reviewFrom(), decision.blockFrom(),
                decision.engineVersion(), decision.eventSnapshot(), outcomes);
    }

    private RowMapper<Decision> decisionMapper() {
        return (rs, rowNum) -> new Decision(
                rs.getObject("decision_id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getString("account_id"),
                new CardToken(rs.getString("card_token")),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getObject("evaluated_at", OffsetDateTime.class).toInstant(),
                Verdict.valueOf(rs.getString("verdict")),
                Verdict.valueOf(rs.getString("decisive_verdict")),
                Verdict.valueOf(rs.getString("score_verdict")),
                rs.getInt("total_score"),
                rs.getInt("review_from"),
                rs.getInt("block_from"),
                rs.getString("engine_version"),
                rs.getString("event_snapshot"),
                List.of());
    }

    private RowMapper<RuleOutcome> outcomeMapper() {
        return (rs, rowNum) -> new RuleOutcome(
                rs.getObject("rule_id", UUID.class),
                rs.getString("rule_code"),
                rs.getInt("rule_version"),
                RuleMode.valueOf(rs.getString("mode")),
                RuleNature.valueOf(rs.getString("nature")),
                OutcomeStatus.valueOf(rs.getString("status")),
                rs.getString("verdict") == null ? null : Verdict.valueOf(rs.getString("verdict")),
                rs.getInt("contribution"),
                rs.getString("reason"));
    }
}
