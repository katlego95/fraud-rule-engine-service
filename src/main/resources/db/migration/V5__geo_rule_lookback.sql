-- The geo rule needs a bounded lookback: without one the candidate query would scan a card's
-- entire history, and "impossible travel" against a transaction from last year is not a signal.
--
-- Added as a new rule version rather than an edit, which is the mechanism ADR D10 describes and
-- the first live exercise of it: version 1 is superseded and retained, and any decision already
-- made under it still pins version 1 and remains explainable.

update rules
set superseded_at = timestamptz '2026-08-03 00:00:01Z'
where code = 'GEO_IMPOSSIBLE' and superseded_at is null;

insert into rules (id, code, version, type, mode, nature, verdict, weight, parameters,
                   description, typology, created_at)
values ('a8000000-0000-4000-8000-000000000082', 'GEO_IMPOSSIBLE', 2, 'GEO_SPEED', 'ACTIVE',
        'DECISIVE', 'BLOCK', null,
        '{"maxSpeedKmh":900,"minDistanceKm":5,"lookbackHours":24}',
        'Two card-present transactions on one card within 24 hours implying travel faster than 900 km/h. Pairs closer than 5 km are ignored, which absorbs coordinate jitter between neighbouring merchants and the identical-timestamp case in one guard.',
        'Cloned card used in a second location', timestamptz '2026-08-03 00:00:01Z');
