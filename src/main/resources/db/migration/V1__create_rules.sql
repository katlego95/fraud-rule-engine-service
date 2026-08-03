-- Rules are versioned by insertion, never by mutation (ADR D10): changing a rule inserts a new
-- row with an incremented version and marks the prior one superseded. Decisions pin the exact
-- id that fired, so a decision made last Tuesday remains explainable under last Tuesday's rules.

create table rules (
    id            uuid        primary key,
    code          text        not null,
    version       integer     not null,
    type          text        not null,
    mode          text        not null,
    nature        text        not null,
    verdict       text,
    weight        integer,
    parameters    jsonb       not null default '{}'::jsonb,
    description   text        not null,
    typology      text        not null,
    created_at    timestamptz not null,
    superseded_at timestamptz,

    constraint rules_code_version_unique unique (code, version),
    constraint rules_mode_valid    check (mode in ('ACTIVE', 'SHADOW', 'DISABLED')),
    constraint rules_nature_valid  check (nature in ('DECISIVE', 'CONTRIBUTORY')),
    constraint rules_verdict_valid check (verdict is null or verdict in ('APPROVE', 'REVIEW', 'BLOCK')),

    -- A decisive rule emits a verdict; a contributory rule carries a weight. Neither carries both,
    -- and a rule carrying neither cannot affect a decision at all.
    constraint rules_nature_payload check (
        (nature = 'DECISIVE'     and verdict is not null and weight is null)
        or (nature = 'CONTRIBUTORY' and weight is not null and verdict is null)
    ),

    -- Scores are integers so that band boundaries are exact. Fractional weights against bands of
    -- 40 and 70 leave gaps that the boundary tests would then have to paper over.
    constraint rules_weight_non_negative check (weight is null or weight >= 0)
);

-- Rule lookup on the evaluation path is "every rule that is not disabled", by code.
create index rules_evaluable_by_code on rules (code) where mode <> 'DISABLED';
