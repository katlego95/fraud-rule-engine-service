package com.fraudengine.decision;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Answers "what would this shadow rule have done?" from decisions already recorded.
 *
 * <p>Nothing is re-evaluated. A shadow outcome stores the weight the rule would have contributed
 * and the verdict it would have demanded, and the decision stores the score and the bands it was
 * judged against — so the counterfactual verdict is arithmetic over rows, not a replay.
 */
@Repository
public class ShadowReportRepository {

    /**
     * Re-derives each decision's verdict with the shadow rule's outcome included.
     *
     * <p>Only outcomes recorded in SHADOW are counted. A rule that was active had its weight
     * inside {@code total_score} already, and adding it a second time would invent verdict changes
     * that never could have happened.
     *
     * <p>The bands come from the decision row rather than configuration: a decision made before
     * the bands were tuned has to be re-derived against the bands it was actually judged by, which
     * is why those columns are pinned on the decision in the first place.
     *
     * <p>One expression covers both natures. A matched contributory rule carries its weight in
     * {@code contribution} and no verdict; a matched decisive rule carries a verdict and a
     * contribution of zero.
     */
    private static final String SQL = """
            with ran as (
                select d.verdict, d.decisive_verdict, d.total_score, d.review_from, d.block_from,
                       o.status, o.contribution, o.verdict as rule_verdict
                from decisions d
                join decision_rule_outcomes o on o.decision_id = d.decision_id
                where o.rule_code = :code
                  and o.mode = 'SHADOW'
                  and d.evaluated_at between :from and :to
            ),
            recomputed as (
                select verdict, status,
                       case when status = 'MATCHED' then total_score + contribution
                            else total_score end as new_score,
                       review_from, block_from,
                       greatest(
                           case decisive_verdict when 'BLOCK' then 3 when 'REVIEW' then 2 else 1 end,
                           case when status = 'MATCHED' then
                               case rule_verdict when 'BLOCK' then 3 when 'REVIEW' then 2 else 1 end
                           else 1 end) as decisive_rank
                from ran
            ),
            banded as (
                select verdict, status, decisive_rank,
                       case when new_score >= block_from then 3
                            when new_score >= review_from then 2
                            else 1 end as score_rank
                from recomputed
            ),
            final as (
                select verdict, status,
                       case greatest(decisive_rank, score_rank)
                            when 3 then 'BLOCK' when 2 then 'REVIEW' else 'APPROVE' end as new_verdict
                from banded
            )
            select count(*)                                                        as evaluated,
                   count(*) filter (where status = 'MATCHED')                      as matched,
                   count(*) filter (where status = 'NOT_MATCHED')                  as not_matched,
                   count(*) filter (where status = 'NOT_EVALUABLE')                as not_evaluable,
                   count(*) filter (where new_verdict <> verdict)                  as would_change,
                   count(*) filter (where new_verdict <> verdict
                                      and new_verdict = 'REVIEW')                  as would_review,
                   count(*) filter (where new_verdict <> verdict
                                      and new_verdict = 'BLOCK')                   as would_block
            from final
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    ShadowReportRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public ShadowReport reportFor(String code, Instant from, Instant to) {
        return jdbc.sql(SQL)
                .param("code", code)
                .param("from", at(from))
                .param("to", at(to))
                .query((rs, rowNum) -> new ShadowReport(code, from, to,
                        rs.getLong("evaluated"),
                        rs.getLong("matched"),
                        rs.getLong("not_matched"),
                        rs.getLong("not_evaluable"),
                        rs.getLong("would_change"),
                        rs.getLong("would_review"),
                        rs.getLong("would_block")))
                .single();
    }

    private OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, clock.getZone());
    }
}
