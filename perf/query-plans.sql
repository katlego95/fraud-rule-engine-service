-- Seed and plan capture for docs/query-plans.md.
--
-- The original script that produced the plans in that document was not kept. This reproduces the
-- same shape: 200,000 events, 5,000 cards, 2,000 accounts, 30 days from 2026-07-01.
--
-- Run against a database whose schema Flyway has already built (start the app once):
--   docker compose exec -T postgres psql -U fraud -d fraud -f - < perf/query-plans.sql
--
-- It only inserts into transaction_events. No decisions, no rules — the plans under test are the
-- velocity reads, and those touch nothing else.

\timing on

-- ---------------------------------------------------------------- seed

insert into transaction_events (
    event_id, occurred_at, account_id, card_token, amount, currency,
    merchant_id, merchant_category_code, merchant_country, channel,
    payload_hash, received_at)
select
    gen_random_uuid(),
    timestamptz '2026-07-01 00:00:00Z' + (random() * interval '30 days'),
    'acct-' || lpad(((i % 2000) + 1)::text, 5, '0'),      -- 2,000 accounts
    'card-' || lpad(((i % 5000) + 1)::text, 5, '0'),      -- 5,000 cards
    round((random() * 5000 + 20)::numeric, 2),
    'ZAR',
    'm-' || (i % 500),
    (array['5411','5812','5999','7995'])[1 + (i % 4)],
    'ZA',
    (array['CARD_PRESENT','ECOMMERCE','ATM'])[1 + (i % 3)],
    md5(i::text),
    now()
from generate_series(1, 200000) as i;

-- VACUUM, not just ANALYZE. An index-only scan needs the visibility map marked, and freshly
-- inserted rows are not all-visible until a vacuum runs — without this the planner falls back to
-- a bitmap heap scan and the plans below look worse than the deployed ones.
vacuum analyze transaction_events;

select count(*) as events,
       pg_size_pretty(pg_total_relation_size('transaction_events')) as size
from transaction_events;

-- ---------------------------------------------------------------- R5: card velocity

-- The hot path. R5, R6 and R8 all read one card's recent events in event-time order.
explain (analyze, buffers, costs off)
select count(*) from transaction_events
where card_token = 'card-01234'
  and occurred_at between timestamptz '2026-07-01' and timestamptz '2026-07-31';

-- The counterfactual: same query, index scans disabled. This is what the index is buying.
set enable_indexscan = off;
set enable_indexonlyscan = off;
set enable_bitmapscan = off;

explain (analyze, buffers, costs off)
select count(*) from transaction_events
where card_token = 'card-01234'
  and occurred_at between timestamptz '2026-07-01' and timestamptz '2026-07-31';

reset enable_indexscan;
reset enable_indexonlyscan;
reset enable_bitmapscan;

-- ---------------------------------------------------------------- R7: account amount velocity

-- Sums amount over a 24-hour window. V6 adds `include (amount)` so the aggregate is index-only
-- rather than one heap fetch per row.
explain (analyze, buffers, costs off)
select coalesce(sum(amount), 0) from transaction_events
where account_id = 'acct-00042'
  and occurred_at between timestamptz '2026-07-01' and timestamptz '2026-07-31';

-- What it looked like before V6: drop the INCLUDE and the amount has to come from the heap.
drop index if exists transaction_events_by_account;
create index transaction_events_by_account on transaction_events (account_id, occurred_at desc);
vacuum analyze transaction_events;

explain (analyze, buffers, costs off)
select coalesce(sum(amount), 0) from transaction_events
where account_id = 'acct-00042'
  and occurred_at between timestamptz '2026-07-01' and timestamptz '2026-07-31';

-- Put V6 back.
drop index transaction_events_by_account;
create index transaction_events_by_account
    on transaction_events (account_id, occurred_at desc) include (amount);
vacuum analyze transaction_events;

-- ---------------------------------------------------------------- cleanup
-- delete from transaction_events;   -- or: docker compose down -v
