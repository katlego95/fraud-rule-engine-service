# ADR 0003 — Testcontainers for integration tests

**Status:** Accepted
**Date:** 2026-08-03

## Context

The integration tests exist to prove the request-to-decision path against the database the service actually deploys on. The common shortcut is to substitute an in-memory database for speed. The problem is that an in-memory substitute differs from PostgreSQL in exactly the places this service depends on: a different SQL dialect, different locking behaviour, different index behaviour, and different type handling. A test suite that passes against a substitute proves the code works somewhere it will never run.

Two features of this project make the substitution concretely unsafe rather than theoretically impure. Rule parameters live in a JSONB column, and H2's JSON support is not PostgreSQL's JSONB — the round-trip through JdbcClient that Phase 1 sets out to prove would be proving the wrong database. And the retrieval API uses keyset pagination over composite indexes, where correctness depends on PostgreSQL's actual ordering and index behaviour; a dialect emulation passing those tests would tell us nothing about the deployed query plans.

## Decision

Integration tests run against real PostgreSQL in a container via Testcontainers 2 (the modules are `testcontainers-postgresql` and `testcontainers-junit-jupiter`; the 1.x coordinates no longer exist). Spring Boot's first-class support does the wiring: `@ServiceConnection` supplies connection properties from the running container automatically, so nothing is hand-configured and nothing can drift. Failsafe runs these separately from the unit tests.

## Alternatives considered

**H2 (or another in-memory database) in PostgreSQL compatibility mode** — declined. The compatibility mode imitates syntax, not semantics: JSONB, locking, index selection and type coercion all diverge, and the first three are load-bearing here. The green build it produces is a false positive, which in a test suite is the most expensive kind of defect.

## Consequences

The tests exercise the real dialect, real migrations (Flyway 12 applies against actual PostgreSQL), and real index behaviour — failures mean something.

The costs are real and accepted. The suite now requires a running Docker daemon; that must be stated plainly in the README, because a reviewer who runs the tests without Docker will conclude the tests are broken, not that a prerequisite is missing. Container startup also makes the suite slower than an in-memory run, and CI must provide Docker. Both are prices worth paying for tests whose passing means the system works where it deploys.
