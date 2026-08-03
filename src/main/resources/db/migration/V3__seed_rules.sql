-- The eight rules, seeded as data rather than code so they can be re-versioned, shadowed or
-- disabled without a redeployment.
--
-- Weights are chosen so that combinations cross the bands meaningfully rather than arbitrarily
-- (bands: < 40 APPROVE, 40-69 REVIEW, >= 70 BLOCK):
--   HIGH_AMOUNT + HIGH_RISK_MCC           = 30  -> APPROVE  two weak signals are not enough
--   HIGH_AMOUNT + CNP_HIGH_AMOUNT         = 40  -> REVIEW   a large card-not-present amount is
--   HIGH_RISK_MCC + CNP_HIGH_AMOUNT       = 40  -> REVIEW
--   + MERCHANT_SPREAD_VELOCITY            = 75  -> BLOCK    three signals plus spread is enough
-- Amounts are in rand; see ADR 0004 for why the service is single-currency.
--
-- Velocity descriptions state the window convention explicitly: the transaction being evaluated
-- is persisted before evaluation and therefore counts inside its own window. Leaving that
-- implicit shifts every threshold by one.

insert into rules (id, code, version, type, mode, nature, verdict, weight, parameters,
                   description, typology, created_at)
values
    ('a1000000-0000-4000-8000-000000000001', 'HIGH_AMOUNT', 1, 'AMOUNT_THRESHOLD', 'ACTIVE',
     'CONTRIBUTORY', null, 15, '{"threshold":"10000.00"}',
     'Transaction amount above R10,000.',
     'General anomaly', timestamptz '2026-08-03 00:00:00Z'),

    ('a2000000-0000-4000-8000-000000000002', 'HIGH_RISK_MCC', 1, 'MCC_SET', 'ACTIVE',
     'CONTRIBUTORY', null, 15, '{"codes":["7995","6051","5967","7273","5933"]}',
     'Merchant category code in the high-risk set: betting, quasi-cash, direct marketing, dating, pawn.',
     'Stolen card usage patterns', timestamptz '2026-08-03 00:00:00Z'),

    ('a3000000-0000-4000-8000-000000000003', 'BLOCKED_COUNTRY', 1, 'COUNTRY_BLOCKLIST', 'ACTIVE',
     'DECISIVE', 'BLOCK', null, '{"countries":["KP","IR"]}',
     'Merchant country on the sanctions blocklist.',
     'Sanctions and known-fraud geographies', timestamptz '2026-08-03 00:00:00Z'),

    ('a4000000-0000-4000-8000-000000000004', 'CNP_HIGH_AMOUNT', 1, 'CHANNEL_AMOUNT', 'ACTIVE',
     'CONTRIBUTORY', null, 25, '{"channel":"ECOMMERCE","threshold":"5000.00"}',
     'Card-not-present transaction above R5,000. Weighted higher than a card-present amount because no one verified the physical card exists.',
     'Card-not-present fraud', timestamptz '2026-08-03 00:00:00Z'),

    ('a5000000-0000-4000-8000-000000000005', 'CARD_TXN_VELOCITY', 1, 'CARD_COUNT_VELOCITY', 'ACTIVE',
     'DECISIVE', 'BLOCK', null, '{"maxCount":4,"windowMinutes":5}',
     'More than 4 authorisations on one card token within the 5 minutes ending at the transaction being evaluated, counting that transaction.',
     'Card testing', timestamptz '2026-08-03 00:00:00Z'),

    ('a6000000-0000-4000-8000-000000000006', 'MERCHANT_SPREAD_VELOCITY', 1, 'MERCHANT_SPREAD_VELOCITY', 'ACTIVE',
     'CONTRIBUTORY', null, 20, '{"maxMerchants":3,"windowMinutes":10}',
     'More than 3 distinct merchants for one card token within the 10 minutes ending at the transaction being evaluated, counting that transaction.',
     'Card testing across merchants to evade single-merchant limits', timestamptz '2026-08-03 00:00:00Z'),

    ('a7000000-0000-4000-8000-000000000007', 'ACCOUNT_AMOUNT_VELOCITY', 1, 'ACCOUNT_AMOUNT_VELOCITY', 'ACTIVE',
     'DECISIVE', 'REVIEW', null, '{"maxAmount":"50000.00","windowHours":24}',
     'Total amount on one account above R50,000 within the 24 hours ending at the transaction being evaluated, including that transaction.',
     'Account takeover, cash-out', timestamptz '2026-08-03 00:00:00Z'),

    ('a8000000-0000-4000-8000-000000000008', 'GEO_IMPOSSIBLE', 1, 'GEO_SPEED', 'ACTIVE',
     'DECISIVE', 'BLOCK', null, '{"maxSpeedKmh":900,"minDistanceKm":5}',
     'Two card-present transactions on one card implying travel faster than 900 km/h. Pairs closer than 5 km are ignored, which absorbs coordinate jitter between neighbouring merchants and the identical-timestamp case in one guard.',
     'Cloned card used in a second location', timestamptz '2026-08-03 00:00:00Z');
