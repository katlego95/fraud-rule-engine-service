# ADR 0002 — Maven over Gradle

**Status:** Accepted
**Date:** 2026-08-03

## Context

The build tool for a take-home assessment serves a different master than the build tool for a long-lived product. The primary reader here is a reviewer forming an impression in fifteen minutes, followed by an interviewer probing decisions for two hours. Whatever is chosen must be legible to both without explanation, and it should look like the estates they work in.

Two facts dominate. First, a `pom.xml` is declarative and boring: a reviewer skims it top to bottom and knows the parent, the dependencies, and the plugins, with nowhere for surprising behaviour to hide. A Gradle build is a program, and reading a program is work. Second, Maven is the convention in South African bank Java estates; a submission to a bank should read as native to that environment, not as an argument for a different one.

## Decision

Maven, using the Spring Boot starter parent for dependency management, with the Failsafe plugin separating integration tests from the unit test run.

## Alternatives considered

**Gradle** — declined, but not because it is worse. For this project it would genuinely have been better at several things: incremental builds and build caching make repeated local runs faster, which matters when a Testcontainers suite is already the slow part; the Kotlin DSL gives type-checked, refactorable build logic; and had this grown into a multi-module project, Gradle's model handles that with less ceremony than Maven's. None of that is disputed. It was declined because every one of those advantages accrues to the maintainer, while every advantage of Maven accrues to the reviewer — and in a submission, the reviewer is the customer.

## Consequences

The build is immediately readable and needs no local toolchain knowledge beyond the wrapper. The whole build configuration is one file a reviewer can audit in a minute.

The accepted costs are Gradle's foregone strengths: slower repeat builds with no build cache, XML verbosity as the dependency list grows, and less expressive power if the build ever needs real logic — custom source sets, conditional wiring — which in Maven tends to mean plugin configuration gymnastics. For a single-module service with a conventional layout, none of these costs bite hard, which is precisely why the trade was cheap to make.
