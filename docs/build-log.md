# Build log

Written at each phase boundary: what was built, why it is shaped that way, and the judgement
calls made along the way. This is interview preparation, not a changelog — it records reasoning
that the code deliberately does not carry.

---

## Phase 1 — skeleton, Docker, Compose, Flyway, stack proof

**What was built.** A Maven project on Spring Boot 4.1.0 and Java 25, a multi-stage Dockerfile
running as a non-root user with a health check, a Compose file whose application container is
gated on the database's health check rather than on start order, the first Flyway migration, and
the tests that prove the parts of the stack that dependency resolution cannot: that Flyway 12
applies against a real PostgreSQL, that a JSONB column round-trips through JdbcClient, and that
BigDecimal amounts serialise as JSON strings without scientific notation.

**Why it is shaped this way.** Phase 1 exists to retire risk, not to produce features. The stack
carries four pieces of novelty at once — Java 25, Boot 4.1, Jackson 3 and Testcontainers 2 — and
each of the three open questions in the brief sits on a path that later phases depend on. Proving
them on day one means a failure costs a fallback decision rather than a rewrite. The alternative,
discovering in Phase 10 that Swagger UI does not render, is not recoverable against a deadline.

### Verified rather than assumed

Every version and coordinate below was read from Maven Central or from published sources today,
because several differ from what a Boot 3 project would use and would otherwise have been wrong:

| Fact | Value | Why it mattered |
|---|---|---|
| Spring Boot | 4.1.0 (only 4.1.x release; 4.1.1 returns 404) | Baseline for everything else |
| Jackson | 3.1.4 (`tools.jackson`), annotations still 2.21.4 (`com.fasterxml.jackson.annotation`) | `@JsonFormat` survives the migration unchanged |
| springdoc | 3.1.0, parent `spring-boot-starter-parent:4.1.0` | The brief assumed 3.0.x/Boot 4.0.x; 3.1.0 shipped 1 Aug 2026 and targets 4.1 directly |
| Testcontainers | 2.0.5 — modules renamed `testcontainers-postgresql`, `testcontainers-junit-jupiter` | The 1.x coordinates `org.testcontainers:postgresql` no longer exist |
| `PostgreSQLContainer` | `org.testcontainers.postgresql` (the `containers` package class is `@Deprecated` in 2.x) | A copied Boot 3 test compiles with a deprecation, or not at all |
| Web starter | `spring-boot-starter-webmvc` | Boot 4 split the web starters; unlike `starter-web` it pulls in `spring-boot-starter` |
| Flyway | 12.4.0, `flyway-database-postgresql` still a separate module | Major version jump from the 11.x on Boot 4.0.x |
| Jackson config hook | `JsonMapperBuilderCustomizer.customize(JsonMapper.Builder)` | Jackson 3 mappers are immutable; the Boot 3 customizer no longer applies |

### Proved at runtime, not merely resolved

The distinction matters because resolution proves only that a dependency graph is satisfiable.
Each of these was executed:

- **springdoc 3.1.0 renders on Boot 4.1.0.** `/v3/api-docs` returns a valid OpenAPI 3.1.0
  document, and `/swagger-ui.html` redirects to `/swagger-ui/index.html`, which serves. This
  closes the brief's first open question empirically: the fallback of hand-writing a minimal
  OpenAPI document was not needed. Worth stating plainly because the brief instructed that
  springdoc compatibility with Boot 4.1 be verified rather than assumed, and because springdoc
  3.1.0 — the first release built against `spring-boot-starter-parent:4.1.0` — was published two
  days before this build. The premise the brief was written under, that the 3.0.x line tracked
  Boot 4.0.x, had just stopped being true.
- **Flyway 12.4.0 applies cleanly** against PostgreSQL 18.4 in a Testcontainer, across the major
  version jump from the 11.x line carried by Boot 4.0.x.
- **Health endpoints** report `UP` with liveness and readiness groups resolving separately, which
  the Docker health check and Compose gating both depend on.
- **Every base image is natively arm64** — `eclipse-temurin:25-jdk`, `eclipse-temurin:25-jre` and
  `postgres:18-alpine` all publish `linux/arm64` manifests, so nothing runs under emulation on an
  Apple silicon host. The Postgres container starts in under a second, which confirms it.

### Judgement calls

**The first migration creates the `rules` table rather than a throwaway.** Phase 1 needs a JSONB
column to prove the round-trip, and inventing a probe table would have meant committing a
migration whose only purpose was to be deleted — against a rule that migrations are never edited
once applied. The rules table is needed in Phase 3 regardless and carries the JSONB `parameters`
column the proof requires, so it is the honest choice. Its constraints encode two decisions taken
early: scores are integers, and a rule is either decisive with a verdict or contributory with a
weight, never both.

**Amounts are configured globally, not per field.** The brief requires amounts to serialise as
strings. Applying `@JsonFormat` field by field means a money field added later without the
annotation silently publishes a number, and silence is the wrong failure mode for money. The
global config override applies to every `BigDecimal` that crosses the boundary.

**`WRITE_BIGDECIMAL_AS_PLAIN` is enabled alongside it.** Jackson serialises `BigDecimal` through
`toString()`, which emits scientific notation above certain scales — an amount published as
`"2.5E+3"` is a defect in a financial API, and it is not hypothetical: the test asserts it. The
trade-off is that plain output throws on an out-of-range scale instead of quietly emitting
notation, which is the failure we want and is stated here so it is a decision rather than a
surprise.

**Exact image tags rather than digests.** The brief permits either. Exact tags keep the build
reproducible for a reviewer without a private mirror; digest pinning is noted in the README as
the production hardening step.

**Compose gates the application on the database health check.** `depends_on` alone waits for the
container to start, not for PostgreSQL to accept connections, and Flyway against an unready
database fails the application. "It worked the second time" is not a deployment story.

**Hikari fails fast.** `initialization-fail-timeout` is set so that running the image without a
reachable database produces a clear failure rather than a silent hang, which the brief calls out
explicitly as a way submissions fail.

---

## Phase 2 — domain model, input contract, validation, Clock

**What was built.** `TransactionEvent` as the input contract, the enums the decision path turns
on, and the `Clock` bean.

**Why it is shaped this way.** The domain is plain Java with no persistence annotations, so the
rules can be reasoned about and unit-tested without a database in the picture. `TransactionEvent`
is a record because it is an immutable value: an event that could be mutated after evaluation
would undermine the audit trail before the audit trail is even written.

**`OutcomeStatus` is three-valued, and that is the point.** `MATCHED`, `NOT_MATCHED`,
`NOT_EVALUABLE`. The brief insists in three separate places that a rule which could not run must
never be indistinguishable from one that ran and did not match, and a boolean cannot carry that.
A missing coordinate reading as "no fraud detected" is the exact failure this type exists to
prevent. Fixing it here rather than at Phase 7 matters because the outcome table is append-only:
discovering the third state later means a data migration on an audit table.

**`Verdict` ordering is load-bearing.** The enum is declared in ascending severity precisely so
composition can take the most severe of the decisive verdict and the score band. That is a
deliberate dependency on declaration order, so it is stated on the type rather than left for a
reader to infer.

**Severity lives on the enum, not in the evaluator.** `Verdict.mostSevere` and
`Channel.impliesPhysicalPresence` sit on the types that own the concept. The channel predicate
exists because impossible-travel reasoning only holds where coordinates describe where the
cardholder physically was — pairing an e-commerce IP location against a card-present location
flags a customer buying online while travelling, which is a false positive the rule should never
generate.

**The `Clock` bean is for evaluation timestamps only.** Velocity windows read the event's own
`occurredAt` and never consult it. It exists so that `evaluatedAt` is substitutable in tests
rather than racing wall time.

**Currency is constrained at the boundary.** See ADR 0004. The contract keeps the ISO 4217 field,
so only the accepted value is narrowed, not the shape.

---

## Phase 3 — rule storage, versioning, modes, seed

**What was built.** The `Rule` record and its repository, the mode-transition audit table, and the
migration seeding all eight rules. Rules live in PostgreSQL as versioned rows, so changing one is
a data operation rather than a deployment.

**Weights were chosen, not guessed.** The brief gives a default for every threshold and window but
not one weight, while asking bands of 40 and 70 to mean something. Seeded so that combinations
cross the bands deliberately: two weak signals (`HIGH_AMOUNT` + `HIGH_RISK_MCC` = 30) stay
`APPROVE`; a large card-not-present amount alongside either (40) reaches `REVIEW`; three signals
plus merchant spread (75) reaches `BLOCK`. A test asserts this arithmetic, so a future weight
change that silently flattens the bands fails the build rather than the customer.

**One `RuleType` per evaluation shape, not per the brief's four families.** Collapsing
`HIGH_AMOUNT` and `HIGH_RISK_MCC` into a shared "threshold" evaluator would need a parameter that
switches between comparing a number and testing set membership. That is the speculative
abstraction the code style rules out: it buys nothing and costs a branch. The families stay a
useful way to *describe* the rule set; they are not a useful way to implement it.

**The window convention is stated in every velocity rule's description.** The transaction being
evaluated is persisted before evaluation and therefore counts inside its own window. Left
implicit, that shifts every threshold by one and quietly changes when each rule fires. It is in
the description because the description is what a call centre agent and an auditor read.

**Mode is operational state, exempt from versioning — and audited because of it.** Moving a rule
between `ACTIVE`, `SHADOW` and `DISABLED` mutates the row rather than inserting a version, which
sits oddly beside "versioned by insertion, never by mutation" unless the exemption is stated. The
cost of the exemption is that "when did this rule leave shadow?" becomes unanswerable, so
transitions are recorded in their own table. In a service whose thesis is auditability, that gap
would have been the obvious question to ask.

**Mode changes assert the affected-row count.** spring-data-relational 4.1.0 no longer throws when
an update matches nothing, so an unknown id would otherwise report success having written
nothing. A redundant change to the mode a rule already has writes no transition at all, which the
tests pin.

**Parameters stay raw JSON on the `Rule`.** Each evaluator deserialises into its own parameter
record, which keeps the type safety at the evaluator rather than inventing a shared parameter
abstraction across eight rules that genuinely differ. The same operation would serve as write-time
validation — "does this parse as this type's parameters" — but it is not wired to the write path,
so a malformed rule is accepted and fails later on the decision path instead. Recorded as a known
gap below rather than described as though it were done.

### Phase 3 addendum — a Testcontainers 2 lifecycle trap worth knowing

Sharing a container through an abstract base class annotated `@Testcontainers` with a static
`@Container` field does not share it: the extension ties the container's lifecycle to the class it
is declared on, stops it when the first subclass finishes, and every later class connects to a
closed port. The suite failed exactly this way. Fixed with the singleton pattern — start the
container in a static initialiser, no extension, and let Ryuk reap it at JVM exit. The suite now
starts one container instead of one per class, which is also why the integration tests run in
seconds rather than tens of seconds.

---

## Phase 4 — stateless evaluators and the composition engine

**What was built.** The four stateless evaluators (R1–R4), the outcome type they produce, and the
engine that turns a list of outcomes into one verdict.

**`RuleOutcome.contribution` records what the rule produced, not what the verdict used.** A
matched contributory rule in shadow mode records its full weight and is excluded during
composition. That inversion is what makes "what would this rule have done?" answerable from the
decision record alone, rather than requiring a reader to re-derive it from a rules table that may
since have changed. Shadow mode is the feature the brief leans on to answer "how do rules get
tuned as fraudsters adapt", and it is worth nothing if its outcomes are recorded as zeroes.

**Composition keeps its working, not just its answer.** `Composition` carries the decisive
verdict, the score verdict, the total and the bands applied — not only the final verdict. A REVIEW
that a decisive rule demanded is a different thing from a REVIEW that three weak signals
accumulated into, and the audit view has to distinguish them. The bands travel with it because
they are configuration: a decision made under one set must stay reconstructable after they move.

**The empty decisive set is stated, not implied.** `reduce(APPROVE, Verdict::mostSevere)` gives a
maximum over nothing an explicit identity. It is also the most common case in production — most
transactions match no decisive rule at all — so leaving it to fall out of a fold would have put a
hole in the path taken by nearly every request.

**Bands are half-open and integral.** `< 40` approves, `40–69` reviews, `>= 70` blocks. The brief
writes them as an inclusive range, which is only a partition if scores are whole numbers; the
schema enforces integer weights so a score can never land between bands. The parameterised test
covers 0, 39, 40, 41, 69, 70 and 200 precisely because that is where this kind of logic fails.

**An unregistered rule type fails loudly.** A rule whose type has no evaluator throws rather than
being skipped or recorded as un-evaluable. Skipping would silently remove a fraud control from
the decision, and `NOT_EVALUABLE` means "the data was missing", not "the deployment was wrong".
Conflating the two would make the audit trail lie about why a control did not run.

**Known consequence of the build order.** The velocity and geo evaluators arrive in Phases 6 and
7, so until then a rule set containing those types cannot be evaluated end to end — by design,
given the fail-loudly choice above. Tests that need a decision path before then disable the
not-yet-implemented rules explicitly rather than the engine tolerating the gap.

---

## Phase 5 — decision persistence, idempotency, audit retrieval

**What was built.** The schema for events, decisions and rule outcomes; the pipeline that turns a
transaction into a persisted, explainable decision; and the read endpoints over it.

### Idempotency is the property this phase exists for

The brief calls it the single most important correctness property in the service, and the naive
implementation — check whether the event exists, then insert it — is wrong in three ways that
only appear under load.

**Insert-and-catch, not check-then-act.** The event identifier is the primary key, and the insert
is `on conflict (event_id) do nothing`. Two concurrent requests carrying the same identifier both
pass a check; only one wins an insert. The loser blocks on the winner's uncommitted row and then
sees zero rows affected. A test races eight threads at one identifier and asserts exactly one
evaluates while seven replay, with one row in `decisions` at the end.

**The event and its decision commit together.** Written in separate transactions, a crash between
them leaves an event that is "already seen" with no decision to return — and because every retry
then takes the replay path and finds nothing, that identifier is poisoned permanently. One
transaction closes the window, and it is also what makes the concurrent case work: when the loser
observes the conflict, the winner's decision is already committed and therefore visible.

**A replay carrying a different body is rejected, not answered.** The submitted event is
fingerprinted and the hash stored. Same identifier plus a different payload returns 409 rather
than the original decision, because silently answering a question nobody asked is worse in a
fraud system than failing. Hashed rather than compared field by field so the check survives every
field the contract grows later.

### The audit record is self-contained by design

`GET /decisions/{id}` returns the input as evaluated, every rule that ran — not only those that
matched — the exact version of each, its mode at the time, its contribution, and its reason. A
reader can reconstruct the decision without consulting the rules table, which matters because the
rules table may have changed. A test proves this directly: it makes a decision, then supersedes
the rule with a new version carrying a different weight, and asserts the stored outcome still
points at the version that actually fired.

Both halves of the composition and the bands in force are columns on the decision, not derived on
read. Bands are configuration; without pinning them, tuning them would silently invalidate every
historical verdict.

### Retrieval

**Keyset, not offset.** The cursor is `(evaluated_at desc, decision_id desc)` — the decision id is
the tiebreaker that makes the order total, and it is in the supporting index for that reason.
Offset pagination degrades linearly and silently skips or repeats rows when data is inserted
mid-scan, which in a service receiving continuous events is always. A test walks seven decisions
three at a time and asserts every row is seen exactly once.

**No total count**, because counting a filtered set on every page request is a scan the caller did
not ask for. **Oversized page sizes are rejected rather than capped**, because a client that asks
for 1000 and receives 200 without being told believes it has seen everything. Errors are RFC 9457
problem details — verified as `application/problem+json` with a field-level breakdown on
validation failures.

**`from` and `to` bound `evaluated_at`, and the listing says so.** The brief describes the sort as
event-time descending while cursoring on the evaluation timestamp; those are different clocks, and
after D5 builds an entire decision on distinguishing them, conflating them here would be the first
thing an interviewer pulls. The listing is an audit view, so it is ordered and filtered by when
the decision was made, named consistently.

### Judgement calls

**Batch is not atomic.** Each item is decided in its own transaction, so one rejected transaction
does not discard decisions already made for the others. That matches the semantics of calling the
single endpoint repeatedly, which is what a caller replaying a sequence expects. Items are sorted
into event-time order before evaluation so a velocity pattern fires correctly regardless of the
order they were supplied in.

**`engine_version` is a configuration property.** Pinned onto every decision so a verdict can be
attributed to the code that produced it. The production answer is the git SHA supplied at build
time; that is noted rather than implemented, because wiring build metadata was not worth the
minutes against this deadline.

**Phase 5 tests disable the velocity and geo rules.** Their evaluators arrive in Phases 6 and 7,
and an unregistered rule type fails loudly by design. The tests state that dependency explicitly
rather than the engine tolerating a missing evaluator — which would mean silently dropping a fraud
control.

---

## Phase 6 — velocity evaluators, indexes, data protection

**What was built.** R5, R6 and R7 with their windowed queries, the covering index that makes R7
index-only, and the data-protection work folded in.

### The window convention, stated once and tested at both edges

The pipeline persists an event before evaluating it, so the transaction under evaluation is
already inside its own window and counts towards its own threshold. That is a real decision, not
an implementation detail: it shifts every threshold by one. Four transactions in five minutes sits
inside the default; the fifth blocks. Each rule's `description` says "including this one" so the
convention reaches the call centre agent and the auditor, not only the code, and tests pin both
the inside and outside edges — including two transactions exactly five minutes apart, where the
earlier one sits on the window's lower boundary and must still count.

### Indexes justified against measured plans

`docs/query-plans.md` carries the output. Against 200,000 events, the card velocity query is an
Index Only Scan touching 4 buffers with zero heap fetches, at 0.13 ms. The same query with index
scans disabled reads 3,435 buffers and takes 16.9 ms *with two parallel workers helping* — on a
synchronous decision path under a 150 ms p99 budget, a sequential scan per velocity rule per
transaction is not survivable.

R7 was the interesting one. The account index located the rows but not `amount`, so the plan was a
bitmap heap scan fetching one heap block per row — 103 buffers. Carrying `amount` as a non-key
`INCLUDE` column makes the aggregate index-only: 5 buffers, 0.087 ms. The trade-off is a larger
index and a write cost on every insert, paid on the hottest read path in the service. It would not
be worth it for a column that is rarely aggregated.

### Data protection

Masking is enforced by the type, not by discipline. `CardToken` renders `tok_****4821` from both
its JSON serialisation and its `toString`, so the unmasked value is reachable only through
`value()` — used by the persistence layer and the velocity queries, nowhere else. A convention
that says "remember to mask when you log" fails the first time someone adds a log line under
pressure; making the unmasked form unreachable removes the opportunity rather than warning against
it. Verified end to end: the API response, the stored event snapshot and `toString` all render
masked.

`deviceId` and `ipAddress` are hashed with a salted SHA-256 before they touch the database.
Nothing queries them and no rule reads them, so a deterministic one-way hash costs nothing
operationally while reducing what a database compromise yields; the same device still hashes to
the same value, so correlation survives.

`cardToken` and `accountId` stay plaintext, deliberately. Velocity windows query by card token and
the retrieval API filters on both — hashing them breaks the queries the service exists to run.
That is a stated residual risk rather than an oversight, and ADR 0005 carries it along with what
production would add: column-level encryption or an encrypted volume, KMS-held keys, rotation, and
the fact that the hash salt is itself a secret that cannot be rotated without invalidating every
existing hash.

Column encryption via pgcrypto was declined outright. Half-implemented encryption is worse than
none: it produces the appearance of protection without key management, rotation, or a recovery
path.

### Fail closed on an unregistered rule type

A rule type with no evaluator now refuses at **startup**, naming the missing type, rather than
failing on the first transaction that happens to touch it. This was a deliberate choice and it is
worth defending explicitly: the alternatives were to skip the rule or to record it as
un-evaluable, and both are wrong. Skipping silently removes a fraud control from every decision —
the system would report a clean APPROVE having never run the rule that would have blocked it.
Recording it as `NOT_EVALUABLE` is a lie of a different kind: that status means "the data was
missing", and using it for "the deployment was wrong" corrupts the one distinction the audit trail
exists to preserve. Refusing to start is the only option that cannot produce a decision that
misrepresents itself.

---

## Phase 7 — geo-impossible travel

**What was built.** R8: haversine distance over the event-time delta between two card-present
transactions on one card.

**Three guards, each removing a class of false positive rather than a single case.** Pairs closer
than the minimum distance are ignored, which absorbs coordinate jitter between neighbouring
merchants, the same-merchant case and the identical-timestamp division by zero in one rule instead
of three special cases — a customer walking between two shops in a mall generates supersonic
implied speeds over twenty seconds without it. Only physically-present channels are paired,
because e-commerce coordinates come from an IP address and pairing one against a card-present
location flags a customer buying online while travelling. And the event under evaluation is
excluded from its own candidate set in SQL, so it cannot pair with itself at zero distance and
zero elapsed time.

**Genuinely simultaneous transactions in two places still match.** Zero elapsed time over a real
distance is not a speed, it is two places at once, and it is handled explicitly rather than
falling through a division guard into a non-match.

**Missing coordinates are `NOT_EVALUABLE`, and so is having no prior position.** Neither is a
non-match. A first transaction on a card has nothing to compare against, and saying "did not
match" would claim the rule ran.

**The lookback arrived as a new rule version, not an edit.** The geo rule needed a bounded
lookback — scanning a card's entire history to compare against a transaction from last year is not
a signal. Rather than editing the applied seed migration, `V5` supersedes version 1 and inserts
version 2 carrying `lookbackHours`. That is the versioning mechanism from ADR D10 exercised for
real rather than only in tests, and any decision already made under version 1 still pins version 1
and remains explainable.

### Known evasion vector, named rather than hidden

Coordinates are optional and client-supplied, and the channel is too. A fraudster who omits
coordinates, or labels a transaction `ECOMMERCE`, turns off a decisive BLOCK rule with their own
payload. The rule records `NOT_EVALUABLE` rather than silently passing, so the gap is visible in
the audit trail — but it is a gap. The production answer is to treat degraded geo data on a
card-present transaction as a small contributory signal in its own right, which is noted as
future work rather than built.

---

## Phase 8 — labelled corpus, seed fixture, scenario endpoints

**What was built.** A fixed-seed generator producing thirty days of ordinary traffic across fifty
accounts plus four injected fraud sequences, each tagged with its typology and the rule that
should catch it; a startup seeder under the demo profile; and demo endpoints that fire a scenario.

**The labels are the deliverable, not the data.** The calibration report in Phase 9a asserts
against `expectedRuleCode`, so a scenario that does not actually exhibit the typology it claims
would turn the report into a fiction that looks like evidence. Each sequence is therefore built to
trip exactly one intended rule: the card-testing burst stays at a single merchant, because
spreading it would also trip the merchant-spread rule and a scenario that trips two cannot show
which one caught it; the merchant-spread sequence is paced so no five-minute window holds more
than four transactions, keeping the card-count rule out of it; the takeover sequence uses six
withdrawals of R9,000, each deliberately below the high-amount threshold, because the point of the
typology is that no single transaction looks unusual and only the aggregate does.

**The background is deliberately not sanitised.** The first version generated legitimate traffic
that could not trip any rule, which produced a flawless 0.00% false positive rate — a number that
looks like a result and means nothing. Real legitimate traffic contains large purchases, online
purchases, and occasional spend at categories the rule set treats as high-risk, because people do
legitimately gamble and buy foreign currency. The corpus now includes those, and the resulting
rate is small but real. A calibration report measured against traffic that cannot trip anything is
as worthless as one measured against sloppy labels.

**Seeded events go through the real decision path**, not raw inserts. Raw inserts are faster but
leave thousands of events that are "already seen" with no decision behind them — breaking the
invariant idempotency depends on, and making `GET /transactions/{eventId}/decision` return 404 for
most of the seeded corpus. Identifiers are deterministic, so restarting an already-seeded database
replays rather than duplicates.

**The demo endpoints are wrappers, not machinery.** They resolve a labelled scenario, rebase it to
now with fresh identifiers so it can be fired more than once, and push it through the same service
the batch endpoint uses. No separate evaluation path, nothing that exists only for the demo except
the lookup itself.

**`structuring` was renamed to `merchant-spread`.** The brief's demo list named a `structuring`
scenario, but no rule in Section 4 targets structuring, and its closest match is the account
aggregate that `account-takeover` already claims — so two of the four scenarios would have shared
one rule and one would have been a no-op. Renaming keeps one scenario per velocity and geo rule.

---

## Phase 9 — observability

**What was built.** A decision timer with percentiles, counters by verdict and by rule fired, a
correlation identifier through MDC, and structured JSON logging.

**Latency is instrumented as a business metric.** The service sits in the authorisation path, so a
slow decision is a decision that arrives after the authorisation it was meant to inform. The timer
publishes p50, p95 and p99 with a histogram bounded at 500 ms — the stated budget is 150 ms, and a
bound an order of magnitude above it keeps the buckets useful rather than flattering.

**`fraud.rule.not_evaluable` has its own counter.** A rule that quietly stops being evaluable —
because an upstream system stopped sending coordinates, say — is a fraud control that has switched
itself off without anyone deciding to. Counting matches alone would not show that; the count would
simply fall.

**Structured logging is native to Boot 4.1** (`logging.structured.format.console`), so there is no
logback XML and no encoder dependency to keep in step. The correlation id reaches every line
through MDC context inclusion.

**Masking needed no logging discipline.** The decision log line prints the card token directly and
still cannot leak one, because `CardToken.toString` masks. That is the payoff of enforcing it in
the type rather than at the call site: the safe thing is what happens when someone writes the
obvious code.

---

## Phase 9a — calibration report and lifecycle tests

**The calibration report is the strongest artefact in the submission**, and it cost about an hour.
It runs the full labelled corpus through the engine and asserts that every injected typology is
caught by its intended rule, that none is caught only by an unintended one, and that the false
positive rate on legitimate traffic stays under 1%. It emits the table that goes in the README.

**Detection and action are reported separately, and that distinction was forced by a failing
test.** The first version asserted that every caught typology produces a non-APPROVE verdict.
`merchant-spread` failed it: R6 is contributory at weight 20, so it matches, records its full
weight, and scores 20 — below the review band. The assertion was wrong, not the engine. A
contributory rule's entire design is that one weak signal is not enough, so asserting it must act
alone would have been asserting against the hybrid model rather than testing it. The report now
labels each intended rule decisive or contributory and only requires the decisive ones to act.

**On knowing the suite is meaningful.** Two tests failed when the velocity rules went live, and
both were expectation errors rather than code defects: one asserted APPROVE for a 99,000 rand
transaction that now legitimately trips the account velocity rule, and one asserted BLOCK while
simultaneously asserting the decisive verdict was REVIEW — a contradiction that only became
reachable once R7 existed. Worth recording because "the tests went red and I changed the tests" is
a claim that deserves scrutiny: the distinction is that in both cases the engine's behaviour was
correct under the composition rules and the assertion described a world where velocity did not
exist yet. The `merchant-spread` failure above is the same category and the most instructive of
the three, because fixing the assertion is what surfaced the detected-versus-acted-on distinction
that now sits in the report.

**The lifecycle tests execute the safe-change narrative rather than describing it**: a candidate
runs in shadow seeing everything and changing nothing, promotion to active changes the verdict
with no restart, a misbehaving rule is disabled back to APPROVE with no deployment, and a decision
made under version 1 still references version 1 after version 2 supersedes it.

---

## Phase 11 — CI, hygiene, and the clean-clone verification

Three real defects were found here, none of which any earlier phase could have found. That is the
argument for the phase existing.

**The Maven wrapper was broken and had never been run.** The wrapper was assembled by hand from the
`bin` distribution, whose `mvnw` shells out to `org.apache.maven.wrapper.MavenWrapperMain` inside a
`maven-wrapper.jar` that was never committed, while `maven-wrapper.properties` declared
`distributionType=only-script`. Every local build had invoked `mvn` directly, so nothing exercised
`./mvnw` until the Docker build did — and the Docker build is what a reviewer runs first. Replaced
with the matching `only-script` variant, which downloads the distribution named in the properties
file and needs no jar in the repository.

**Running without a database printed eighty stack frames.** The brief names this failure mode
explicitly, and it is the most likely way a reviewer meets the service. A `FailureAnalyzer` now
matches `java.sql.SQLException` and branches on SQLSTATE: class 08 explains that the service needs
a database and gives both start commands, class 28 points at the credentials instead so a wrong
password is not reported as an unreachable host. Matched on `SQLException` rather than a socket
exception because a refused connection arrives as `ConnectException` and an unroutable host as
`SocketTimeoutException` — targeting either alone leaves the other unhandled — and SQLSTATE is
standard, so it needs no compile dependency on a driver that is runtime-scoped.

**A timestamp precision bug that only exists on Linux.** CI failed on
`resubmittingTheSameEventReturnsTheOriginalDecision`:

    expected: 2026-08-03T13:23:52.024179586Z
     but was: 2026-08-03T13:23:52.024180Z

`evaluatedAt` was generated at nanosecond precision while PostgreSQL `timestamptz` stores
microseconds, so the decision returned from an evaluation carried a timestamp the stored row did
not have — a caller who posted a transaction and then read it back saw two different timestamps
for one decision. macOS clock granularity is microseconds, so the two agreed by luck throughout
development; Linux exposed it immediately.

The fix is in the code, not the assertion: `evaluatedAt` is truncated to microseconds at creation
so the value returned and the value stored are the same value. Worth being precise about why that
is the right way round — the test was asserting something true and useful (an evaluation and its
round-trip describe one decision), and weakening it to a tolerance would have preserved the defect
while hiding the evidence.

**Verified, in this order:** fresh clone into an empty directory; `docker compose up --build`
starting and seeding 1,276 transactions plus 4 scenarios in 1.5 s, ready in 5 s; all eight
walkthrough commands run in sequence with output matching what the README claims; `docker build`
and `docker run` independently of Compose, reaching a database over an environment variable;
the no-database failure path; `./mvnw test` and `./mvnw verify` from a clone of the public URL;
and CI green on a machine that is not mine.

---

## Known gaps and next steps

Recorded here rather than left implicit, because a named gap is defensible and a discovered one is
not.

- **Wire `RuleParameters.read` into the rule write path** so a malformed rule is rejected with 400
  at write time rather than surfacing as a 500 on the decision endpoint. The typed parameter
  records already exist and already do this work at evaluation time; the write path simply does
  not call them. See ADR 0007.
- **Rules are loaded from the database on every evaluation.** There is no cache. At this scale that
  is a query per decision against an indexed primary-key table, which is affordable, but it is the
  obvious first optimisation. Adding one introduces the multi-instance propagation problem — two
  instances briefly disagreeing after a rule change — which would need a short TTL, an
  invalidation event, or a documented convergence window.
- **The backtest endpoint** replaying candidate rules against stored history: deferred for time,
  not unconsidered. The evaluator is already a pure function of rule, event and history.
