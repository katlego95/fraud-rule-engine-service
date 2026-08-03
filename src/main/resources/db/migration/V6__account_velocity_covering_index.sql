-- R7 sums amount over an account's 24-hour window. The original index locates the rows but not
-- the amount, so the plan was an index scan followed by a heap fetch per row — 103 buffers for a
-- 100-row window. Carrying amount as a non-key column makes the aggregate index-only.
--
-- The trade-off is a larger index and a write-side cost on every insert, paid on the hottest read
-- path in the service. Worth it here; it would not be for a column that is rarely aggregated.

drop index transaction_events_by_account;

create index transaction_events_by_account
    on transaction_events (account_id, occurred_at desc) include (amount);
