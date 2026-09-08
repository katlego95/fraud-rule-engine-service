-- Both rules count distinct values inside a window, so the counted column is carried as a
-- non-key column: without it the plan finds the rows by index and then fetches each one from the
-- heap to read the value it is counting. Same reasoning as V6.
--
-- Partial, because both columns are nullable — a card-present transaction has no IP — and null
-- entries are index weight that no query will ever look up.

create index transaction_events_by_ip on transaction_events (ip_address, occurred_at desc) include (card_token)
where ip_address is not null;

create index transaction_events_by_device on transaction_events (device_id, occurred_at desc) include (account_id)
where device_id is not null;


-- Seeded in SHADOW, not ACTIVE. Neither threshold has been measured against real traffic — 5
-- cards per IP and 3 accounts per device are informed guesses. In shadow both rules evaluate and
-- their outcomes are recorded on every decision, but they change no verdict, so the thresholds
-- can be tuned against observed behaviour and promotion becomes a separate, reversible decision.
--
-- CONTRIBUTORY, not DECISIVE. Both identifiers are shared by innocent populations: a corporate
-- NAT gateway, a mobile carrier, a family tablet. One match is evidence, not proof, so each
-- carries a weight that needs corroboration from another signal to reach a band.

insert into rules (id, code, version, type, mode, nature, verdict, weight, parameters,
                   description, typology, created_at)
values
    ('a9000000-0000-4000-8000-000000000009', 'IP_CARD_SPREAD', 1, 'IP_CARD_SPREAD_VELOCITY', 'SHADOW',
     'CONTRIBUTORY', null, 20, '{"maxCount":5,"windowMinutes":10}',
     'More than 5 distinct card tokens from one IP address within the 10 minutes ending at the transaction being evaluated, counting that transaction.',
     'Card testing from a single source', timestamptz '2026-09-07 00:00:00Z'),

    ('aa000000-0000-4000-8000-000000000010', 'DEVICE_ACCOUNT_SPREAD', 1, 'DEVICE_ACCOUNT_SPREAD_VELOCITY', 'SHADOW',
     'CONTRIBUTORY', null, 25, '{"maxCount":3,"windowHours":24}',
     'More than 3 distinct accounts from one device within the 24 hours ending at the transaction being evaluated, counting that transaction.',
     'Mule networks and account takeover', timestamptz '2026-09-07 00:00:00Z');