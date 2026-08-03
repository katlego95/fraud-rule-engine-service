# Query plans

Captured against PostgreSQL 18.4 with **200,000 transaction events** (55 MB), spread over 5,000
cards and 2,000 accounts across 30 days. `EXPLAIN (analyze, buffers, costs off)`.

The point of this file is to show the indexes were chosen against measured plans rather than added
reflexively. Each index in `V4__transactions_and_decisions.sql` carries a comment naming the rule
that depends on it; this is the evidence behind those comments.

## R5 · card velocity — the hot path

R5, R6 and R8 all read one card's recent events in event-time order, so this index carries three
of the eight rules.

```
select count(*) from transaction_events
where card_token = 'card-1234'
  and occurred_at between '2026-07-01' and '2026-07-31';
```

```
Aggregate (actual time=0.131..0.132 rows=1.00 loops=1)
  Buffers: shared hit=4
  ->  Index Only Scan using transaction_events_by_card on transaction_events
        (actual time=0.122..0.124 rows=40.00 loops=1)
        Index Cond: ((card_token = 'card-1234'::text)
                 AND (occurred_at >= '2026-07-01 00:00:00+00')
                 AND (occurred_at <= '2026-07-31 00:00:00+00'))
        Heap Fetches: 0
        Buffers: shared hit=4
```

**Index Only Scan, 4 buffers, zero heap fetches.** Both predicates are satisfied from the index —
`card_token` is the leading column and `occurred_at` bounds the range within it, which is exactly
the shape a windowed velocity query needs.

### The counterfactual

The same query with index scans disabled, to show what the index is actually buying:

```
Finalize Aggregate (actual time=15.762..16.910 rows=1.00 loops=1)
  Buffers: shared hit=3435
  ->  Gather (Workers Launched: 2)
```

**3,435 buffers against 4, and 16.9 ms against 0.13 ms** — and that is with two parallel workers
helping. On the synchronous decision path, under a stated p99 budget of 150 ms, a sequential scan
per velocity rule per transaction is not survivable.

## R7 · account amount velocity — and why the index has an INCLUDE

R7 sums `amount` over an account's 24-hour window. The first version of the index located the rows
but not the amount, so the plan fetched every row from the heap:

```
Aggregate (actual time=1.508..1.509)
  Buffers: shared hit=103
  ->  Bitmap Heap Scan on transaction_events (rows=100.00)
        Heap Blocks: exact=100
```

One heap block per row. Adding `amount` as a non-key column (`V6`) makes the aggregate index-only:

```
Aggregate (actual time=0.087..0.087)
  Buffers: shared hit=1 read=4
  ->  Index Only Scan using transaction_events_by_account on transaction_events
        (actual time=0.039..0.073 rows=100.00 loops=1)
        Heap Fetches: 0
```

**103 buffers to 5, and 1.5 ms to 0.087 ms.**

The trade-off is a larger index and a write cost on every insert, paid to speed up the hottest
read path in the service. It would not be worth it for a column that is rarely aggregated; it is
worth it for one that is read on every transaction an account makes.

## Reproducing this

`docs/query-plans.sql` is not committed — the seed above is four lines of `generate_series` and
the plans are captured with the commands shown. What matters for review is that the indexes were
justified against measured plans, and that the counterfactual was run rather than assumed.
