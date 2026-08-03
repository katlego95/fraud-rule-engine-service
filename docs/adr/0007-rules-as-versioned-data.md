# ADR 0007 — Rules as versioned data, not a rule engine framework

**Status:** Accepted
**Date:** 2026-08-03

## Context

The brief asks for a set of fraud rules applied per transaction. The question underneath it is where those rules live.

If fraud logic sits as conditionals inside compiled Java, changing a threshold from R10,000 to R8,000 requires a code change, a review, a build, a test cycle and a deployment. In a bank that is days, and the pattern that prompted the change is running the whole time. Worse, the people who understand the fraud are risk analysts, and this arrangement makes every change require an engineer.

The domain makes this urgent rather than merely tidy. Rule-based systems need constant tuning because fraudsters learn to operate just below static thresholds. A rule engine without a safe, fast way to change rules has solved half the problem and left the expensive half.

So rules must be configuration rather than code. The remaining question is whether to adopt an existing engine or model them directly.

## Decision

Rules are stored in PostgreSQL as typed, versioned rows: a discriminator selecting the evaluator, type-specific parameters in JSONB, a mode, a nature, a weight or verdict, and a human-readable description. A purpose-built evaluator interprets them. No third-party rule engine.

Four properties fall out of the data model rather than being built on top of it:

- **Versioning by insertion.** A change creates a new row; the prior version is retained and marked superseded. Decisions reference the version that actually fired.
- **Shadow mode.** A mode column is sufficient to evaluate a rule and record its outcome without letting it act.
- **Runtime change.** Mode and parameters change through the API with no restart and no deployment.
- **Replay.** Because the evaluator is a pure function of rule, event and history, a stored rule version can be re-run against stored events.

## Alternatives considered

**Drools, or Red Hat Decision Manager** — the obvious candidate, mature, and used in banks. Declined for four reasons.

Grab reached the same conclusion building Griffin, their production anti-fraud rule engine, which now serves billions of predictions daily at over 100,000 queries per second. They evaluated Drools and built their own instead: DRL carries a non-trivial learning curve for the people expected to author rules, and Drools suits a static dataset while their rules needed one whose shape changed over time. Both apply here.

Second, the Rete algorithm underneath Drools optimises pattern matching across large rule sets by caching partial matches. There are eight rules. The optimisation is irrelevant while its complexity is not.

Third, versioning, shadow mode and replay would have to be bolted onto Drools rather than falling out of it. The mechanisms this service most needs are the ones the framework least provides.

Fourth, and decisively for a service whose product is explainability: if the engine is a library, "why was this transaction flagged?" is answered by someone else's design. The reasoning would be inherited rather than owned.

**A generic expression language** — SpEL, MVEL or CEL evaluating a condition string like `amount > 5000 && merchantCountry != 'ZA'` at runtime. Genuinely attractive: far lighter than Drools, and it would allow rules whose shape was not anticipated. Declined because an arbitrary expression is not statically checkable, so an invalid rule becomes a runtime failure on the decision path rather than a rejected write; because expression strings resist the typed parameter validation that catches a bad threshold at the API boundary; and because arbitrary expressions over the event model are an injection surface in a service that accepts rule definitions over HTTP. Typed rule types trade expressiveness for safety, which is the right trade at eight rules and the wrong one at eighty.

**Rules as conditionals in code** — declined per the context. Fast to write, and it makes every subsequent change a deployment.

## Consequences

The thesis is demonstrable in thirty seconds: list the rules, disable one, resend the same transaction, receive a different verdict. No restart, no rebuild.

The costs are real. Adding a genuinely new *kind* of rule still requires code, because a new type needs a new evaluator — this design makes configuration free and extension cheap, not free. JSONB parameters also move schema enforcement out of the database and into the application, and that enforcement is currently incomplete: parameters are validated at evaluation time rather than on write. That is a real gap. A malformed rule write is accepted and surfaces as a failure on the decision path, which is the same failure mode cited above as a reason for declining expression languages. The typed design permits write-time validation; it is simply not yet wired to the write path.

The evaluator registry fails closed: an unregistered rule type refuses at startup rather than being skipped. Skipping would remove a fraud control while still reporting a clean `APPROVE`, and recording it as `NOT_EVALUABLE` would be a different lie — that status means the input data was missing, and reusing it for a deployment error would corrupt the one distinction the audit trail exists to preserve.

## References

- Grab Engineering, *Griffin, an anti-fraud risk rule engine making billions of predictions daily* — https://engineering.grab.com/griffin
- Red Hat Developer, *Detecting credit card fraud with Red Hat Decision Manager 7* — https://developers.redhat.com/blog/2018/07/26/detecting-credit-card-fraud-with-red-hat-decision-manager-7
- Databricks, *Payment fraud detection* — https://www.databricks.com/blog/payment-fraud-detection
