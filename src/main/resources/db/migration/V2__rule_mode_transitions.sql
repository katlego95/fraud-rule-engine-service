-- Mode is operational state, not rule definition, so it is exempt from the versioning rule in
-- ADR D10: moving a rule between ACTIVE, SHADOW and DISABLED mutates the row rather than
-- inserting a version. That exemption costs an audit trail unless one is kept deliberately —
-- "when did this rule leave shadow?" has to be answerable in a service whose thesis is
-- auditability.

create table rule_mode_transitions (
    id         uuid        primary key,
    rule_id    uuid        not null references rules (id),
    from_mode  text        not null,
    to_mode    text        not null,
    changed_at timestamptz not null,

    constraint rule_mode_transitions_modes_differ check (from_mode <> to_mode)
);

-- Every read of this table is "the history of one rule, most recent first".
create index rule_mode_transitions_by_rule on rule_mode_transitions (rule_id, changed_at desc);
