# ADR 0004 — Single-currency scope: ZAR only

**Status:** Accepted
**Date:** 2026-08-03

## Context

The input contract carries an ISO 4217 currency field, because real transaction events carry one. But the amount-based rules are denominated in rand: R1 and R4 compare against rand thresholds, and R7 sums total amount on an account across a 24-hour window against a default of R50,000. Sum a rand amount and a dollar amount and the result has no unit; compare it against a threshold that does and the verdict is meaningless. The failure is silent — a mixed-currency transaction scored against rand thresholds is a wrong decision presented as a right one, and nothing in the output betrays it.

The alternative to rejecting is converting, and conversion makes an FX rate source a hard dependency of the fraud decision path — with its own staleness, its own failure modes, and the question of which rate applied at which moment becoming part of an audit record that must be reconstructable years later.

## Decision

The service accepts ZAR transactions only. Any other currency is rejected at the API boundary with a validation error. The contract still carries the ISO 4217 field, so the shape of the multi-currency contract is already in place; only the accepted value is constrained.

This is the same category of decision as declaring transaction categorisation upstream: drawing a boundary deliberately and stating it, rather than pretending to handle something the service cannot honestly handle. Rejecting loudly at the boundary beats silently mis-scoring.

## Alternatives considered

**Accept all currencies and score them against the same thresholds** — declined. Silently wrong, in the way described above; the worst kind of wrong for a system whose entire value is explainability.

**Convert to ZAR at ingest via an FX source** — declined for this assessment, for the dependency and audit costs stated in the context. It is, however, the correct production answer once the FX source exists.

**Make the rules currency-aware immediately** — per-currency thresholds and windows. Declined as scope an assessment with eight rules does not justify, though it is half of the real answer.

## Consequences

The near-term consequence is simple: one validation constraint, one clear error, no wrong decisions.

Supporting multi-currency later changes four things. Every amount-summing rule becomes a per-currency window — R7 aggregates per (account, currency) rather than per account, so a pattern split across currencies is not missed by a naive sum. Thresholds become per-currency configuration rather than single values in rule parameters. An FX source joins the decision path, bringing rate staleness, an unavailability failure mode (fail open or fail closed — both bad in different ways), and the requirement to pin the rate used onto the decision record so the decision stays reconstructable. And honestly: per-currency windows fragment velocity detection — an attacker who spreads activity across currencies sits below every individual window, which is why a real deployment should convert rather than isolate.
