package com.fraudengine.rules;

import com.fraudengine.domain.Rule;
import com.fraudengine.domain.RuleMode;
import com.fraudengine.domain.RuleNature;
import com.fraudengine.domain.RuleType;
import com.fraudengine.domain.Verdict;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RuleRepository {

    private static final String COLUMNS = """
            id, code, version, type, mode, nature, verdict, weight, parameters::text as parameters,
            description, typology, created_at, superseded_at
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    RuleRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The evaluation set: current versions that are not disabled. Shadow rules are included. */
    public List<Rule> findEvaluable() {
        return jdbc.sql("select " + COLUMNS + " from rules where superseded_at is null and mode <> 'DISABLED' order by code")
                .query(mapper())
                .list();
    }

    public List<Rule> findCurrent() {
        return jdbc.sql("select " + COLUMNS + " from rules where superseded_at is null order by code")
                .query(mapper())
                .list();
    }

    public List<Rule> findHistory(String code) {
        return jdbc.sql("select " + COLUMNS + " from rules where code = :code order by version desc")
                .param("code", code)
                .query(mapper())
                .list();
    }

    public Optional<Rule> findById(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from rules where id = :id")
                .param("id", id)
                .query(mapper())
                .optional();
    }

    /**
     * Supersedes the current version of this code, if one exists, and inserts the next version.
     * Both statements share a transaction so a rule can never be left with two current versions
     * or none.
     */
    @Transactional
    public Rule insertNextVersion(Rule rule) {
        Instant now = clock.instant();
        int nextVersion = jdbc.sql("select coalesce(max(version), 0) + 1 from rules where code = :code")
                .param("code", rule.code())
                .query(Integer.class)
                .single();

        jdbc.sql("update rules set superseded_at = :now where code = :code and superseded_at is null")
                .param("now", OffsetDateTime.ofInstant(now, clock.getZone()))
                .param("code", rule.code())
                .update();

        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into rules (id, code, version, type, mode, nature, verdict, weight, parameters,
                                   description, typology, created_at)
                values (:id, :code, :version, :type, :mode, :nature, :verdict, :weight,
                        cast(:parameters as jsonb), :description, :typology, :createdAt)
                """)
                .param("id", id)
                .param("code", rule.code())
                .param("version", nextVersion)
                .param("type", rule.type().name())
                .param("mode", rule.mode().name())
                .param("nature", rule.nature().name())
                .param("verdict", rule.verdict() == null ? null : rule.verdict().name())
                .param("weight", rule.weight())
                .param("parameters", rule.parameters())
                .param("description", rule.description())
                .param("typology", rule.typology())
                .param("createdAt", OffsetDateTime.ofInstant(now, clock.getZone()))
                .update();

        return findById(id).orElseThrow();
    }

    /**
     * Moves a rule between modes and records the transition.
     *
     * <p>The affected-row count is asserted rather than ignored: spring-data-relational 4.1.0 no
     * longer throws when an update matches nothing, so an unknown id or a no-op mode change would
     * otherwise return success having written nothing.
     */
    @Transactional
    public Rule changeMode(UUID id, RuleMode target) {
        Rule current = findById(id).orElseThrow(() -> new RuleNotFoundException(id));
        if (current.mode() == target) {
            return current;
        }

        int updated = jdbc.sql("update rules set mode = :mode where id = :id")
                .param("mode", target.name())
                .param("id", id)
                .update();

        if (updated != 1) {
            throw new IllegalStateException("Expected to update 1 rule, updated %d for id %s".formatted(updated, id));
        }

        jdbc.sql("""
                insert into rule_mode_transitions (id, rule_id, from_mode, to_mode, changed_at)
                values (:id, :ruleId, :fromMode, :toMode, :changedAt)
                """)
                .param("id", UUID.randomUUID())
                .param("ruleId", id)
                .param("fromMode", current.mode().name())
                .param("toMode", target.name())
                .param("changedAt", OffsetDateTime.now(clock))
                .update();

        return findById(id).orElseThrow();
    }

    private RowMapper<Rule> mapper() {
        return (rs, rowNum) -> new Rule(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getInt("version"),
                RuleType.valueOf(rs.getString("type")),
                RuleMode.valueOf(rs.getString("mode")),
                RuleNature.valueOf(rs.getString("nature")),
                rs.getString("verdict") == null ? null : Verdict.valueOf(rs.getString("verdict")),
                rs.getObject("weight") == null ? null : rs.getInt("weight"),
                rs.getString("parameters"),
                rs.getString("description"),
                rs.getString("typology"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("superseded_at", OffsetDateTime.class) == null
                        ? null
                        : rs.getObject("superseded_at", OffsetDateTime.class).toInstant());
    }
}
