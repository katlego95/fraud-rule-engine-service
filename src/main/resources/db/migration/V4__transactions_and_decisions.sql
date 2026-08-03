-- The event is the idempotency anchor and the velocity substrate; the decision is the audit
-- record. Both are append-only: an editable audit trail is not one.

create table transaction_events (
    -- Client-supplied and the primary key, so idempotency is enforced by the database rather
    -- than by a check-then-insert that two concurrent requests can both pass.
    event_id               uuid           primary key,
    occurred_at            timestamptz    not null,
    account_id             text           not null,
    card_token             text           not null,
    amount                 numeric(18, 2) not null,
    currency               text           not null,
    merchant_id            text           not null,
    merchant_name          text,
    merchant_category_code text           not null,
    merchant_country       text           not null,
    channel                text           not null,
    latitude               double precision,
    longitude              double precision,
    device_id              text,
    ip_address             text,
    category               text,
    -- Detects a replay that carries a different body. Returning the original decision for a
    -- different payload would answer a question nobody asked.
    payload_hash           text           not null,
    received_at            timestamptz    not null
);

-- The velocity hot path. R5 (count per card), R6 (distinct merchants per card) and R8 (position
-- pairs per card) all scan one card's recent events in event-time order.
create index transaction_events_by_card on transaction_events (card_token, occurred_at desc);

-- R7 sums one account's amounts over a 24-hour event-time window.
create index transaction_events_by_account on transaction_events (account_id, occurred_at desc);

create table decisions (
    decision_id      uuid           primary key,
    -- One decision per event, enforced rather than assumed. Also the lookup for replay and for
    -- GET /transactions/{eventId}/decision, which is why it is a unique index and not a plain one.
    event_id         uuid           not null unique references transaction_events (event_id),
    account_id       text           not null,
    card_token       text           not null,
    occurred_at      timestamptz    not null,
    evaluated_at     timestamptz    not null,
    verdict          text           not null,
    -- Both halves of the composition, so a REVIEW that a decisive rule demanded stays
    -- distinguishable from a REVIEW that weak signals accumulated into.
    decisive_verdict text           not null,
    score_verdict    text           not null,
    total_score      integer        not null,
    -- The bands are configuration. Without pinning them the verdict cannot be re-derived once
    -- they are tuned, and D9 requires a decision to remain reconstructable.
    review_from      integer        not null,
    block_from       integer        not null,
    engine_version   text           not null,
    -- The input as evaluated, so the audit view is self-contained.
    event_snapshot   jsonb          not null,

    constraint decisions_verdict_valid check (verdict in ('APPROVE', 'REVIEW', 'BLOCK'))
);

-- The default listing and the keyset cursor, which orders on (evaluated_at desc, decision_id desc).
-- decision_id is in the index because it is the tiebreaker that makes the order total.
create index decisions_by_evaluated_at on decisions (evaluated_at desc, decision_id desc);

-- Filter-supporting indexes. Every filter the retrieval API offers leads with an indexed column;
-- combinations narrow residually within the leading filter's range.
create index decisions_by_account on decisions (account_id, evaluated_at desc, decision_id desc);
create index decisions_by_card on decisions (card_token, evaluated_at desc, decision_id desc);
create index decisions_by_verdict on decisions (verdict, evaluated_at desc, decision_id desc);

create table decision_rule_outcomes (
    id           uuid    primary key,
    decision_id  uuid    not null references decisions (decision_id),
    -- The exact rule version that ran. A decision made last Tuesday was made under last
    -- Tuesday's rules, and this is what makes that reconstructable.
    rule_id      uuid    not null references rules (id),
    rule_code    text    not null,
    rule_version integer not null,
    mode         text    not null,
    nature       text    not null,
    -- Three-valued: a rule that could not run must never look like one that ran and did not match.
    status       text    not null,
    verdict      text,
    contribution integer not null,
    reason       text    not null,

    constraint decision_rule_outcomes_status_valid
        check (status in ('MATCHED', 'NOT_MATCHED', 'NOT_EVALUABLE'))
);

-- The audit view reads every outcome for one decision.
create index decision_rule_outcomes_by_decision on decision_rule_outcomes (decision_id);

-- Supports ?ruleCode= on the retrieval API: find decisions where a named rule fired.
create index decision_rule_outcomes_by_rule_code
    on decision_rule_outcomes (rule_code, decision_id) where status = 'MATCHED';
