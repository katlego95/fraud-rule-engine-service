# ADR 0006 — Batch evaluation: event-time ordered, deliberately non-atomic

**Status:** Accepted
**Date:** 2026-08-03

## Context

The brief specifies the batch endpoint in one sentence: evaluate an ordered sequence, processed in event-time order, enables scenario replay. It says nothing about what happens when the third item of five fails, and leaving that undecided would be worse than choosing either way — a caller cannot use an endpoint whose failure behaviour is unknown.

Two prior decisions constrain the answer. D5 makes every velocity window relative to each event's own `occurredAt`, so the order in which events are persisted determines what a later event's window contains — a later item's verdict legitimately depends on earlier items in the same batch having been persisted first. D7 makes every event idempotent by `eventId`, so re-submitting an event replays its stored decision rather than double-counting it.

## Decision

`POST /api/v1/decisions/batch` accepts a list of transaction events, sorts them into event-time order, and evaluates each through the same `decide` path as the single endpoint — each in its own database transaction, since `decide` is `@Transactional` and the batch adds no outer one. It returns one decision per item, in the order evaluated. It is deliberately not atomic.

The batch is a convenience over repeated calls to the single endpoint, not a new transactional unit. Calling the single endpoint five times and having the fourth fail leaves three decisions standing; the batch behaves identically, otherwise the two paths would disagree about what a failure means. Atomicity would also be actively wrong here: rolling the whole batch back on one bad item would discard correct decisions already returned to the caller, and unwinding a returned decision is not something an append-only audit trail should do.

Sorting is required by D5, not a convenience. A sequence supplied out of order would produce a different — and wrong — set of verdicts than the same sequence in order. Sorting makes the output depend on the events, not on how the caller happened to serialise them.

## Alternatives considered

**All-or-nothing in one transaction** — declined for the reasons above, and because it would hold one database transaction open across an unbounded number of evaluations: a lock-duration problem at any real batch size.

**Preserve caller-supplied order** — declined. Makes verdicts depend on serialisation order, contradicting D5.

**Reject an unsorted batch with 400 instead of sorting** — a genuine toss-up. More explicit, and it pushes the ordering contract onto the caller rather than fixing it silently. Declined because the endpoint's stated purpose is scenario replay, where sorting is the helpful behaviour — but this one could defensibly have gone the other way.

## Consequences

A partial failure leaves the decisions already made standing and returns an error. The caller's recovery is simple: resubmit the whole batch. Items that already succeeded replay by `eventId` rather than double-count — this is the property that makes non-atomic safe rather than merely convenient.

Because each item commits independently, a reader querying mid-batch can observe a partially applied sequence. That is correct for this design, but worth stating.

And a known gap: no cap on batch size is enforced. An unbounded list is a denial-of-service surface and a memory risk, and a production deployment must close it.

A partial-success response shape — returning the decisions that did commit alongside the error
that stopped the rest — is the production answer, and it is deliberately not built here. As
implemented, `decideBatch` collects the whole stream before responding, so a mid-batch failure
returns only the error while the decisions already made stand in the database. That is recoverable
(resubmit the batch; idempotency replays the successes) but it is not discoverable from the
response, which is the real shortcoming. Fixing it properly means designing a response envelope
that carries per-item status, deciding the HTTP status code for a mixed result, and documenting
how a caller distinguishes "not attempted" from "attempted and failed" — a contract decision worth
more care than the remaining schedule allowed. Deferred consciously rather than overlooked.
