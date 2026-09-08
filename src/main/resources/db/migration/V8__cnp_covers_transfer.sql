-- CNP_HIGH_AMOUNT was named for a category and implemented for one member of it.
--
-- Card-not-present means the cardholder is not physically present, which is ECOMMERCE *and*
-- TRANSFER. The rule matched ECOMMERCE only, so a R50,000 transfer never tripped the rule named
-- after exactly that typology. `Channel.impliesPhysicalPresence()` already encoded the right
-- distinction; the geo rule used it and this rule did not.
--
-- Fixed by widening the rule rather than renaming it. The code is denormalised onto every
-- decision_rule_outcomes row and is what ?ruleCode= filters on, so renaming would orphan the
-- history of every decision this rule has already influenced. Widening keeps one code, one
-- typology, and an accurate name.
--
-- A new version rather than an edit, the mechanism ADR D10 describes and V5 already exercised:
-- version 1 is superseded and retained, and decisions already made under it still pin version 1
-- and remain explainable under the parameters that actually applied.

update rules
set superseded_at = timestamptz '2026-09-08 00:00:00Z'
where code = 'CNP_HIGH_AMOUNT' and superseded_at is null;

insert into rules (id, code, version, type, mode, nature, verdict, weight, parameters,
                   description, typology, created_at)
values ('a4000000-0000-4000-8000-000000000042', 'CNP_HIGH_AMOUNT', 2, 'CHANNEL_AMOUNT', 'ACTIVE',
        'CONTRIBUTORY', null, 25,
        '{"channels":["ECOMMERCE","TRANSFER"],"threshold":"5000.00"}',
        'Card-not-present transaction above R5,000, on any channel where the cardholder is not physically present. Weighted higher than a card-present amount because no one verified the physical card exists.',
        'Card-not-present fraud', timestamptz '2026-09-08 00:00:00Z');
