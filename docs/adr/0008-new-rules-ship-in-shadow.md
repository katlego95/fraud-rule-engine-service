# ADR 0008 — New rule types ship in shadow, not active

**Status:** Accepted
**Date:** 2026-09-07

## Context

Two typologies were added after the original eight: `IP_CARD_SPREAD`, which counts distinct cards seen from one IP address, and `DEVICE_ACCOUNT_SPREAD`, which counts distinct accounts seen from one device. Both target patterns the existing rules miss — card testing run through a single machine, and one operator working a set of accounts.

Both also arrive with a problem the original eight did not have to state out loud: nobody knows whether the thresholds are right. Five cards per IP in ten minutes and three accounts per device in twenty-four hours are informed guesses. They were chosen by reasoning about the typology, not by measuring traffic.

That would matter less if the identifiers were unambiguous. They are not. An IP address is shared by everyone behind a corporate NAT gateway, a mobile carrier's egress, a university, a coffee shop. A device is shared by a family. A rule keyed on a shared identifier does not decline one fraudster; it declines every innocent person behind the same identifier, at the same moment, for the same reason.

The existing rules already encode a related judgement — `CARD_TXN_VELOCITY` blocks decisively — but a card token identifies one instrument held by one person. The blast radius of being wrong is one cardholder. For an IP it is everyone on that gateway.

## Decision

New rule types are seeded in `SHADOW` mode and `CONTRIBUTORY` nature. Promotion to `ACTIVE` is a separate decision, made against observed behaviour rather than at the time the rule is written.

**Shadow.** The rule is evaluated on every decision and its outcome is recorded in full — including, when it matches, the weight it would have contributed. `RuleOutcome.countsTowardsVerdict()` then excludes it during composition, so it changes no verdict. The question "what would this rule have done?" is answerable from the stored decision record, without rerunning anything.

**Contributory.** A match adds to a score rather than dictating an outcome, so a shared identifier needs corroboration from an independent signal before a transaction is held. A single match on a NAT gateway reaches 20 against a review band of 40.

## Alternatives considered

**Ship active, tune after the first false positives.** The feedback is real and immediate, which is its appeal. Declined because the feedback arrives as declined transactions belonging to people who did nothing wrong, and because the cost is paid disproportionately by whoever happens to sit behind a shared address. Shadow mode obtains the same information at no cost to anyone.

**Ship active but decisive-BLOCK, like `CARD_TXN_VELOCITY`.** Declined on blast radius, as above. The precedent does not transfer: that rule keys on a card token, which identifies one person.

**Do not seed the rules at all; add them through the API once calibrated.** Declined because it leaves the evaluator code with nothing exercising it, and because a rule that exists only in someone's notes is not reviewable. Seeded in shadow, the rule is in the migration, in the audit trail of every decision, and under test.

**Withhold the rules until a threshold can be measured.** Declined as circular — the measurement requires the rule to be running, which is what shadow mode is for.

## Consequences

The rules are live in the sense that matters for learning and inert in the sense that matters for customers. Every decision made from now on carries an outcome row for both, so the data needed to choose a threshold accumulates from the first transaction.

The gap this exposes is tooling, not model. The outcomes are recorded per decision, so "how often would `IP_CARD_SPREAD` have fired last week, and on which transactions?" is a query that can be written — but there is no report, endpoint or dashboard that answers it. Until that exists, promotion out of shadow would rest on someone writing ad-hoc SQL, which is a weaker basis than the mechanism deserves. Building that report is the natural next piece of work, and it is worth more than the next rule.

Promotion itself is unguarded. Any caller who can change a rule's mode can move a rule from shadow to active in one request, with no second approval and no percentage rollout. That is consistent with the service's stated scope — authentication and authorisation are out of scope — so there is no identity to require a second of.

## References

- Grab Engineering, *Griffin, an anti-fraud risk rule engine making billions of predictions daily* — https://engineering.grab.com/griffin
- Databricks, *Payment fraud detection* — https://www.databricks.com/blog/payment-fraud-detection
- ADR 0005 — data protection, for why device and IP are stored hashed and what that costs the rules
- ADR 0007 — rules as versioned data, for the mode column these rules rely on


---

## Update — 2026-09-08

**This is now enforced on the write path.** The original text conceded that the
safety described here was "a convention held up by the seed migration, not a
control the system enforces". `RuleService` enforces it:

| Case | Mode |
|---|---|
| A new code | must be **SHADOW** — anything else is a 400 |
| A new version of an existing code | must keep the mode in force — anything else is a 400 |

The second half matters as much as the first. Creating a version supersedes its
predecessor, so a version submitted as SHADOW against a rule that is ACTIVE would
take that rule out of the verdict — a fraud control switched off as a side effect
of editing a threshold, reported to the caller as a success. Editing a rule and
changing what it does are separate operations, which is why mode has its own
endpoint.

Rejected rather than silently corrected, on the same reasoning the page-size limit
uses: a caller who asked for ACTIVE and was quietly given SHADOW believes the rule
is deciding when it is only watching.

**What this required, and it is the interesting part.** The rules path had no
service layer, correctly — the controller validated and the repository wrote, and
there was nothing in between worth a layer. Enforcing this changes that: deciding
a new version's mode means reading the version in force and then writing, and a
read-then-write needs a transaction around it or a concurrent mode change lands
between the two. `RuleService` exists because the policy arrived, not because the
pattern called for it.

**Still not enforced:** promotion. Moving a rule out of shadow remains one
unauthenticated call, and percentage rollout between shadow and active does not
exist.
