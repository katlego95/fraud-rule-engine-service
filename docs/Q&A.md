# Q&A

Answers to questions raised while reading the source articles.
Short answers. Detail lives in the ADRs and the build log.

**Measured one request at a time, against the seeded corpus:**

| | |
|---|---|
| Rule evaluation, mean | 3.39 ms |
| Rule evaluation, p99 | ~7 ms |
| Full HTTP round trip, median | 9.1 ms |
| Full HTTP round trip, p95 | 26.8 ms |
| Stated budget | 150 ms p99 |

Laptop, one instance, requests one at a time. **Not a load test** — it measures
latency, never throughput. There is now a real one: 1,500 rps with zero errors,
the p99 budget holding to 100 concurrent callers and gone by 150. See
[`load-test.md`](load-test.md).

---

# Griffin

## Q. Griffin has shadow mode, 1%→100% rollout, change approval, version control with rollback, and RBAC. What do we have?

| Griffin | Us |
|---|---|
| Shadow mode | ✅ `RuleMode.SHADOW` |
| Reading back what shadow did | ✅ `GET /rules/{code}/shadow-report` — matches, and how many verdicts would have moved |
| Version control | ✅ new row per change, old one kept |
| Rollback | ⚠️ partial — old versions are kept, but there is no "revert" button |
| 1% → 100% rollout | ❌ none |
| Change approval (2 people) | ❌ none |
| RBAC | ❌ none — auth is out of scope, stated in the README |
| Shadow enforced on creation | ❌ none — ADR 0008 says new rules ship in shadow, but `POST /rules` accepts `ACTIVE` |

**Rollback path:** the data is already there. Add `POST /rules/{code}/revert/{version}` that copies an old version's parameters into a new version. Five lines. Never delete, always move forward.

**Percentage rollout path:** add a `sample_percent` column. Hash the card token, mod 100, compare. Deterministic — the same card always gets the same treatment, so a customer doesn't flip between rules mid-session.

**Approval path:** rules need a `PENDING` mode and a second endpoint that only a different user can call. Needs authentication first, which we scoped out.

---

## Q. Do we have a Data Orchestration layer like Symphony?

**Found in:** the Griffin article (https://github.com/grab/symphony)

**What Symphony is:** a Go library that works out which data fetches depend on which, then runs everything it can at the same time. Grab needed it because some models want 200+ inputs per prediction.

**It is not a rule engine.** It gathers inputs. It does not decide.

**Do we have one?** No — and we do not need one.

| | Grab | Us |
|---|---|---|
| Data per decision | 200+ | 4 database queries |
| Sequential cost | fatal | ~3 ms total |

**Where our version of this problem lives:** `RuleEngine.evaluate()` runs rules one after another with `.stream().map()`. Four of the eight query the database.

**The answer to "what happens at fifty rules?"**

> Rules run sequentially and four hit the database. Fine at eight — each is an index-only
> scan at 4 buffers. At fifty it would not be. The fix is to run independent rules in
> parallel and batch queries that share a key. Grab built Symphony for exactly this.

**The caveat that shows you understood it:** our evaluators fetch *and* judge in the same class. You would first split "get the data" from "judge the data", then parallelise the first part. Symphony does not drop in as-is.

---

## Q. Griffin has a UI so a non-programmer can write rules. How do we change rules?

**Today: a REST API.** Four endpoints on `RuleController`:

| | |
|---|---|
| `GET /api/v1/rules` | list current rules |
| `GET /api/v1/rules/{code}/history` | every version of one rule |
| `POST /api/v1/rules` | create a rule, or a new version |
| `PATCH /api/v1/rules/{id}/mode` | ACTIVE / SHADOW / DISABLED |

So a person changes a rule with a `curl` command, not a form. That is the gap.

**Path to a UI:** the hard part is already done — rules are data, and each rule type has a fixed parameter shape (`AmountThresholdEvaluator.Parameters` is just `threshold`). Generate a form from that shape, one form per rule type. Add the pending/approval state above. The UI is then a thin layer over endpoints that already exist.

**Why we did not build it:** backend role, backend submission. It is in the README's next steps as *"rule authoring for risk analysts, which is the point of rules-as-data."*

---

## Q. What is our equivalent of Griffin's checkpoint, segment, treatment, counter?

| Griffin | Us | Note |
|---|---|---|
| **Rule** | `rules` table row | same idea |
| **Checkpoint** (group of rules + default outcome) | our whole active rule set + `ScoreBands` | we have one checkpoint, not many |
| **Treatment / Action** (what to do when it fires) | `Verdict` — APPROVE / REVIEW / BLOCK | ours are three fixed outcomes; Griffin's are open-ended (send notification, block booking…) |
| **Scenario** (when to evaluate) | one trigger: `POST /decisions` | Griffin has PreBooking, PostBooking, etc. |
| **Segment** (limit a rule to a slice of traffic) | ❌ nothing | closest is `CNP_HIGH_AMOUNT` which only applies to one channel — but that is inside the rule, not a separate concept |
| **Counter** | our velocity queries | Griffin pre-computes counters; we count live with SQL |

**The honest summary:** Griffin is a platform with many trigger points and open-ended actions. Ours is one trigger point with three outcomes. That is the right size for eight rules, and the missing pieces (segments, multiple scenarios) are the ones you would add first if it grew.

---

## Q. Where is our latency, and how does this scale to millions?

**Where the time goes, per decision:**

1. Hash the payload — microseconds
2. Insert the event — one write
3. **Load the rules — one database query** ⚠️
4. Evaluate 8 rules — **4 of them each run a query** ⚠️
5. Compose — pure maths, microseconds
6. Insert the decision + 8 outcome rows — one write

So roughly **6 database round trips per decision.**

**What we did to make it fast:**
- Indexes chosen against measured query plans, not guesswork. The velocity query is an index-only scan at 4 buffers / 0.13 ms. With the index disabled it is 3,435 buffers / 16.9 ms — a 130× difference (`docs/query-plans.md`).
- `V6` added `INCLUDE (amount)` so the account-sum query never touches the table itself: 103 buffers → 5.

**What we did not do: there is no cache.** The rules are re-read on every decision. Stated in ADR 0007.

**The path to millions:**

| Step | Why |
|---|---|
| 1. Cache the rule set in memory | Removes 1 of 6 round trips immediately. Rules change rarely, are read constantly |
| 2. Run independent rules in parallel | The 4 database rules do not depend on each other |
| 3. Redis for velocity counters | The real fix. Counting live in SQL is correct but is the hot path |
| 4. Horizontal scaling | The service holds no state, so add instances |

**The cost of step 1, which you should raise before they do:** with a cache, two instances can briefly disagree after a rule change. Fix with a short TTL or an invalidation event, and document the convergence window.

**The cost of step 3:** a cold cache after deployment silently under-detects until it refills. That failure mode is worth naming unprompted.

---

## Q. Griffin says "use built-in functions, written in C, no one can beat it." Does that apply to us?

**Their point:** in Python, code you write yourself is slow; the functions built into the language are written in C and are fast. So prefer the built-in.

**In Java it barely applies.** Java compiles to bytecode and the JIT compiler optimises hot code at runtime, so a loop you write and a library method perform similarly. There is no equivalent gap.

**The Java version of the same instinct — spend nothing on the hot path:**
- Our composition step is plain arithmetic — sums and comparisons, no allocation of consequence
- `Verdict.mostSevere` is an enum ordinal comparison
- `CardToken` masking builds a string only when something is serialised or logged
- The real cost is not CPU, it is the 6 database round trips

**The honest answer:** in a Java service like this, micro-optimising code is the wrong place to look. Every millisecond that matters is I/O. That is why the work went into index design and why the measured numbers are dominated by queries.

---

# Databricks — Payment fraud detection

## Q. Which typologies do we cover, and which do we miss?

The article names six. We target three.

| Typology | Us |
|---|---|
| **Card testing** | ✅ R5 (count per card), R6 (spread across merchants) |
| **Account takeover / cash-out** | ✅ R7 (total per account in 24h) |
| **Card-not-present fraud** | ✅ R4 (online + large amount) |
| **Authorised push payment (APP)** | ❌ victim is tricked into paying willingly — no card event to inspect |
| **Friendly fraud** | ❌ customer disputes a genuine purchase — only visible weeks later at chargeback |
| **Gift card fraud** | ❌ partially reachable — `HIGH_RISK_MCC` could include gift-card merchant codes |

Plus two rules not tied to a typology in that list: `HIGH_AMOUNT` (general anomaly) and `BLOCKED_COUNTRY` (sanctions), and `GEO_IMPOSSIBLE` for cloned cards.

**Why the three we miss are genuinely hard, not just unbuilt:**
- **APP fraud** needs the *victim's* behaviour and beneficiary history, not the transaction
- **Friendly fraud** is only knowable after a dispute, so it is a feedback-loop problem, not a real-time rule
- Both need data this service does not receive

**Path:** these need new inputs, not new rules. APP fraud would need beneficiary account age and payment history. Friendly fraud needs chargeback outcomes fed back — which is also what would let us measure whether our rules are actually right.

---

## Q. Do we have behavioural analytics? What is the path?

**No, none.** We look at one transaction and the history of that card or account. We never look at *how the person behaved* — typing cadence, mouse movement, session timing.

**We do not receive that data.** `TransactionEvent` has no session, no timing, no interaction fields.

**Path, in order:**
1. Capture it upstream — behavioural signals come from the app or web session, not the payment message
2. Deliver a summary with the transaction, e.g. `sessionRiskScore`
3. Consume it as a contributory rule with a weight

**Why that shape:** the score comes from a model, but the *decision* stays a rule with a weight you can read. Same pattern as the ML answer below.

---

## Q. The article says scoring must complete under 100 ms. Are we?

**Yes, comfortably.**

| Measure | Result |
|---|---|
| Rule evaluation, mean | 3.39 ms |
| Rule evaluation, p99 | ~7 ms |
| HTTP round trip, median | 9.1 ms |
| HTTP round trip, p95 | 26.8 ms |

**How we timed it:** a Micrometer timer wraps the evaluation inside `DecisionService`, publishing a histogram at `/actuator/prometheus`. The numbers above come from 1,694 recorded decisions.

**For comparison:** Griffin reports predictions under 6 ms and p99 under 30 ms — but at 100,000 queries per second across six servers.

**Be honest about what this measurement is not:** one laptop, one instance, requests sent one at a time, against a table holding the 1,293-event corpus plus whatever that session had added. It shows the design is not inherently slow. It measures latency and never throughput, so it is not a load test — [`load-test.md`](load-test.md) is.

---

## Q. Do we do device fingerprinting?

**No fingerprinting** — we do not derive a device signature from browser or hardware characteristics. We receive `deviceId` and `ipAddress` from the caller, hash them, and store them.

**Two rules now read them** (added since this was first written): `IP_CARD_SPREAD` counts distinct cards per IP, `DEVICE_ACCOUNT_SPREAD` counts distinct accounts per device. Both in shadow.

**Why they are hashed:** a deterministic salted SHA-256 (ADR 0005) keeps equality, which is all a counting rule needs, while a stolen table yields nothing readable. What it costs is structure — no subnet or prefix matching, only exact equality.

**The trap that cost a debugging session:** evaluators receive the **raw** event while the columns hold fingerprints, so a rule must hash before it queries. Miss that and the lookup matches nothing, returns zero, and reads as a clean transaction — a rule that never fires and never errors.

**Still missing:** real fingerprinting. A `deviceId` supplied by the client is only as trustworthy as the client.

Real fingerprinting — browser and hardware characteristics — is a separate upstream product. We would consume its output as an identifier, not compute it.

---

## Q. The article describes a four-layer stack. Which layers are we?

| Layer | Us |
|---|---|
| Network controls (rate limiting, IP reputation) | ❌ upstream concern, typically a gateway |
| Authentication (MFA, device binding) | ❌ out of scope, stated |
| **Transaction scoring (real-time)** | ✅ **this is us, entirely** |
| Post-authorisation monitoring (chargebacks, disputes) | ❌ needs outcome data we never receive |

**One layer of four, and that is the correct scope.** The useful thing to say is what the layers on either side would give us: network controls would stop the volume before it arrives; chargeback monitoring would finally tell us whether our verdicts were *right*, which nothing in this system currently knows.

---

## Q. How do we handle false positives? What did we learn?

**A false positive is a real customer wrongly flagged.** Blocking someone's groceries costs a call-centre ticket and possibly the relationship.

**Three things in the design address it:**

1. **Three verdicts, not two.** REVIEW exists so the answer to "slightly suspicious" is not "declined."
2. **Contributory rules.** A weak signal alone cannot block. It has to combine with others to cross a band.
3. **Shadow mode.** A new rule is watched against live traffic before it is allowed to act.

**We measure it.** The calibration report runs 1,276 legitimate transactions through the engine and reports the false positive rate. Currently **0.47%** — 6 of 1,276.

**What we actually learned — and this is the best story in the project.** The first run reported **0.00%**, which looked like success and was worthless: the fake background traffic had been generated too clean to trip any rule. A rule set measured against traffic that cannot trip it always scores perfectly.

The lesson: *a test that cannot produce a bad number is decoration.* The corpus now includes large purchases and legitimate gambling spend, so the rate is small but real.

**Second thing we learned:** `MERCHANT_SPREAD_VELOCITY` is detected but approves, because it is contributory at weight 20 and lands below the review band. The first version of the test failed on this. The **test** was wrong, not the engine — forcing a contributory rule to act alone would abandon the hybrid model.

---

## Q. Threshold optimisation experiments and manual review queues — do we do these?

**Threshold experiments: partly.** The calibration report is the measuring instrument — change a weight, re-run, see the false positive rate move. What is missing is the revenue side: we count flagged transactions, not the money lost by declining good ones. Real optimisation weighs fraud prevented against revenue declined, and we have no revenue data.

**Manual review queues: no.** We produce the REVIEW verdict but nothing consumes it. There is no queue, no reviewer screen, no outcome recorded.

**Path:** REVIEW decisions are already queryable — `GET /decisions?verdict=REVIEW` is the queue's data source. What is missing is a reviewer marking each one fraud or not, and that outcome flowing back. That single feedback loop is what would turn our synthetic calibration into real calibration.

---

## Q. How would we measure the KPIs?

| KPI | Can we measure it? |
|---|---|
| **False positive rate** | ✅ 0.47%, from the calibration report |
| **Fraud detection rate** | ✅ against labelled data — 4 of 4 injected typologies caught |
| **Decision latency** | ✅ p99 ~7 ms, from Micrometer |
| **Verdict mix** | ✅ counters per verdict |
| **Rule hit rate** | ✅ counter per rule |
| **Fraud rate** (real fraud as % of volume) | ❌ needs confirmed fraud outcomes |
| **Chargeback rate and fees** | ❌ arrives weeks later, from a different system |
| **Mean time to detection** | ❌ needs a known fraud start time |

**The pattern:** we can measure everything about *our own behaviour*. We cannot measure anything requiring the *real answer*, because nothing tells us whether a decision was correct.

**That is not a gap in the code, it is a missing feedback loop** — chargebacks and analyst outcomes flowing back in. Worth saying plainly: today's accuracy numbers come from data we generated ourselves, so they demonstrate the method, not production accuracy.

---

# Red Hat — Decision Manager

## Q. Where in our system is "fetch the context of that transaction from a datastore"?

**`VelocityRepository`.** That class is the entire context-fetching layer — four queries:

| Method | Fetches | Used by |
|---|---|---|
| `countCardTransactions` | how many on this card in the window | R5 |
| `countDistinctMerchants` | how many merchants on this card | R6 |
| `sumAccountAmount` | total spent on this account | R7 |
| `positionsExcluding` | previous locations for this card | R8 |

**Key difference from the Drools example:** their example holds transactions in memory in a "working memory" session and matches patterns across it. We keep everything in PostgreSQL and ask a question per rule.

**Why ours is slower per rule but better here:** their approach is faster, but the state disappears on restart and does not survive across instances. Ours is durable, correct after a crash, and needs no extra infrastructure. Stated in the brief as D8.

---

# PXP — Velocity checks

## Q. How do we use velocity rules, and do we have a blocklist?

**Our three velocity rules:**

| Rule | Identifier | Counts | Window | Fires at | Action |
|---|---|---|---|---|---|
| R5 `CARD_TXN_VELOCITY` | card token | transactions | 5 min | > 4 | BLOCK |
| R6 `MERCHANT_SPREAD_VELOCITY` | card token | distinct merchants | 10 min | > 3 | +20 to score |
| R7 `ACCOUNT_AMOUNT_VELOCITY` | account | total amount | 24 h | > R50,000 | REVIEW |

The window is measured from the transaction's own timestamp, and **includes the transaction being evaluated** — so four in five minutes passes and the fifth blocks.

**Blocklist:** we have one, but only for countries. `BLOCKED_COUNTRY` holds a list in its rule parameters. We have **no dynamic blocklist** — nothing adds a card or device to a list automatically when a rule fires.

**Path:** a `blocked_identifiers` table (type, value, reason, expiry, the decision that caused it) plus a rule type that checks it. The important design point: entries must expire, and must record which decision added them — otherwise you have built a way to permanently block customers with no audit trail.

---

## Q. Attackers spread attempts across cards and IPs to beat single-identifier rules. What is our path?

**This is our biggest detection gap, and it is worth naming first.**

The three original velocity rules key on **one identifier at a time** — card token or account. An attacker using each stolen card once, from one device, defeats all of them: every card looks like a first-time transaction.

**That gap is now partly closed.** `IP_CARD_SPREAD` and `DEVICE_ACCOUNT_SPREAD` key on the source rather than the card, which is exactly the shape this attack evades. Both are in shadow, so they observe without acting yet.

**Path, cheapest first:**

1. ~~**Device velocity.**~~ **Done** — `DEVICE_ACCOUNT_SPREAD`, distinct accounts per device over 24 hours, in shadow.
2. ~~**IP velocity.**~~ **Done** — `IP_CARD_SPREAD`, distinct cards per IP over 10 minutes, in shadow. Contributory rather than decisive, because a corporate NAT gateway is one IP and blocking on it takes out everyone behind it.
3. **BIN velocity.** See below.
4. **Merchant velocity.** Many cards hitting one merchant in minutes is the merchant-side signature of the same attack.

**The one-line answer:** *"Card-level velocity is defeated by spreading across cards. The fix is device-level velocity, and the data is already stored and hashed — it needs a rule, not a migration."*

---

## Q. The article says authorisation velocity per card and per BIN is the first line of detection. Do we cover it?

**Per card: yes** — R5, though with a caveat below.

**Per BIN: no.** A BIN is the first six to eight digits of a card number, identifying the issuing bank and card type. All cards from one breached batch often share a BIN, so BIN velocity catches a whole stolen batch being tested.

**Why we cannot do it today:** we receive `cardToken` — an opaque replacement for the card number. **The BIN is not recoverable from a token.** That is the point of tokenisation.

**Path:** the tokenisation service would have to pass the BIN alongside the token as a separate field. It is not sensitive on its own — it identifies a bank, not a person. Then BIN velocity is just another rule.

**The caveat on "attempts":** the article says count *authorisation attempts*, because fraudsters generate failed attempts in volume while legitimate customers generate successes. **We count every transaction we receive and do not know which were declined.** Our input contract has no outcome field. Adding one would make R5 both more accurate and less likely to catch real customers. It is in our next steps.

---

## Q. The article lists identifiers to monitor. Which do we cover?

| Identifier | Received? | Used by a rule? |
|---|---|---|
| Card token | ✅ | ✅ R5, R6, R8 |
| Account | ✅ | ✅ R7 |
| Merchant | ✅ | ✅ R6 counts distinct merchants |
| Merchant country | ✅ | ✅ R3 |
| Merchant category | ✅ | ✅ R2 |
| Location | ✅ optional | ✅ R8 |
| **Device ID** | ✅ | ❌ stored, hashed, unused |
| **IP address** | ✅ | ❌ stored, hashed, unused |
| BIN range | ❌ | ❌ not recoverable from a token |
| Email, billing address, shipping address | ❌ | ❌ not in the contract |
| Session ID, browser fingerprint | ❌ | ❌ not in the contract |

**Two-sentence summary:** *"We monitor card, account, merchant, geography and category. Device and IP are received and stored but no rule uses them yet — that is the first gap I would close, and it needs no schema change."*

---

# Databricks — Anomaly detection

## Q. Why did we not take the machine learning approach?

**Three reasons, in order of strength.**

**1. A model cannot explain itself.** The article's approach is an Isolation Forest — it outputs a score, not a reason. A rule produces a sentence: *"5 transactions on this card in 5 minutes, threshold 4."* That sentence is what a call-centre agent reads to a customer and what a regulator asks for. Our whole audit design exists to preserve it.

**2. We would be training on our own invention.** The only data available is the corpus we generated. A model trained on it would learn the patterns we told the generator to produce — and then we would "discover" them. Circular, and worthless as evidence.

**3. The brief asked for rules.** ML was explicitly out of scope.

**Do not overstate it — the article recommends both.** The Databricks article on payment fraud says financial institutions combine rules, machine learning and behavioural analytics in a layered stack. It does not say rules are enough.

**So the honest position is:**

> Not rules *instead of* ML. The right shape is a model score arriving as one input
> to a contributory rule with a weight — so the decision layer stays explainable while
> the model contributes what it is good at. That is what I would build next if given
> real historical data.

**One more parallel worth having:** the article says the model *"needs to be retrained on new data as it arrives"* because behaviour changes over time. That is the same decay problem as rules going stale — models drift, rules are gamed. Neither is a set-and-forget system, which is exactly why shadow mode and versioning matter either way.

---

# Sparkov dataset

## Q. Is this the dataset our system is based on, and why?

**We did not use the data. We copied the shape.**

`TransactionEvent`'s fields — amount, merchant, category code, location, timestamp — follow the Sparkov schema, which was built to resemble real card transactions. That is the answer to "why these fields and not others": provenance, not invention.

**But every row of data is generated by our own code**, in `SyntheticCorpus`: 1,276 legitimate transactions across 50 accounts over 30 days, plus 4 labelled fraud scenarios, from a fixed seed so it is identical on every run.

**Why generate rather than download:**

| Dataset | Why not |
|---|---|
| **ULB** | 28 of 31 columns are PCA-scrambled into `V1, V2, V3…`. You cannot write an explainable rule about `V17` — it means nothing to a human |
| **IEEE-CIS** | Behind competition registration. A reviewer would need a Kaggle account before the project runs |
| **Sparkov** | Good schema, but a download step — and it carries no labels for the specific typologies our rules target |

**The decisive reason:** we needed **labels we control**. The calibration report asserts that each injected fraud scenario is caught by the rule it was labelled with. That only works if we know exactly which typology each sequence represents — which means constructing them deliberately.

**State the limitation before they do:** deliberately constructed sequences demonstrate the calibration *method*, not production accuracy. Real threshold tuning needs real historical data.
