# ADR 0001 — Java 25 and Spring Boot 4.1

**Status:** Accepted
**Date:** 2026-08-03

## Context

This is a payments-adjacent service submitted to a bank. The framework line it ships on is itself a defensibility question: the Spring Boot 3.5 line reached end of life on 30 June 2026, which leaves 4.x as the only branch under open-source support. Shipping fraud tooling on an unsupported framework is not a position that survives scrutiny in this domain, however familiar the older line might be.

The Java version is a separate decision, and it is worth being precise about why. Boot 4.1's actual baseline is Java 17, with compatibility through 26 — nothing about the framework forces Java 25. Choosing 25 is deliberate: it is the current released LTS, and an assessment is exactly the place to demonstrate fluency with the current platform rather than the one that was current three years ago.

## Decision

Build on Java 25 (LTS) with Spring Boot 4.1.0, the only published 4.1.x release at the time of writing.

## Alternatives considered

**Spring Boot 3.5 on Java 21** — the comfortable option, with more community mileage and more answers on Stack Overflow. Declined because the line is end-of-life; comfort on an unsupported branch is borrowed, not owned.

**Kotlin** — better personal fluency, and a defensible language for this domain. Declined because the role is explicitly Java and the interview will be conducted in Java terms.

## Consequences

The benefit is a defensible foundation: every framework dependency is on a supported line, and that can be said plainly in the README.

The cost is a concentration of novelty risk. Four pieces of the stack are new at once: Java 25, Boot 4.1, the Jackson 3 baseline that Boot 4 carries, and Testcontainers 2. Several coordinates genuinely differ from a Boot 3 project — Jackson moved to `tools.jackson`, the web starter split into `spring-boot-starter-webmvc`, the Testcontainers 1.x module coordinates no longer exist — so copied Boot 3 idioms fail rather than degrade. Phase 1 was shaped to retire exactly this risk: every version and coordinate was read from Maven Central rather than assumed, and the first tests written target the novel seams — Flyway 12 against real PostgreSQL, JSONB round-tripping, BigDecimal serialisation under Jackson 3 — so a failure there costs a fallback decision, not a rewrite discovered at the deadline.
