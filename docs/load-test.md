# Load test

The README quoted latency measured one request at a time. That is not a load
test. This is one.

---

## The words, first

Everything below uses five terms. They are all simple.

| Term | Plain English |
|---|---|
| **Latency** | how long **one** request takes, start to finish. Measured in milliseconds (ms). |
| **Throughput** | how **many** requests finish per second. Written **rps** — requests per second. |
| **Concurrency** | how many requests are **in flight at the same time**. k6 calls one of these a **VU** — a virtual user, a fake client sending requests back to back. 100 VUs is 100 simultaneous callers. |
| **p95 / p99** | percentiles. **p99 = 128ms** means 99 of every 100 requests finished in 128ms or less, and the slowest 1 took longer. |
| **Budget** | the promise. This service states a **150ms p99** — 99% of decisions inside 150ms. |

**Why percentiles and not an average.** An average hides the bad cases. Ninety-nine
requests at 10ms and one at 5 seconds averages to **60ms** — which looks healthy,
and isn't: one customer waited five seconds at a card machine. p99 would report
that request instead of burying it. Percentiles describe the unlucky tail; averages
describe the comfortable middle.

**Latency and throughput are not the same thing, and they trade off.** Adding
concurrent callers usually raises throughput — more work finishing per second —
while raising latency, because each request now queues behind others. That
trade-off is exactly what the table below measures.

**Reading one k6 result.** A run prints something like:

```
http_reqs......: 133108  1477.60/s     ← 133,108 requests, 1,478 per second (throughput)
http_req_duration: avg=67ms p(95)=85ms  ← latency: typical 67ms, 95th percentile 85ms
http_req_failed: 0.00%  0 out of 133108 ← errors: none
THRESHOLDS: p(99)<150  p(99)=130ms  ✓   ← the budget, and whether it held
```

Four numbers to look at, in order: **failures** (is it broken?), **p99** (is it
within budget?), **rps** (how much is it doing?), then the rest.

---

## Findings

**1. It never fails. It only gets slower.**

Zero server errors at every level tried, up to 200 concurrent users and 1,600
requests per second. No **5xx** — HTTP status codes in the 500s, meaning the
server failed — no timeouts, no exceptions in the application log. Under load the
service degrades by taking longer, not by refusing work.

**2. It breaks its own latency promise between 100 and 150 concurrent.**

The README states a 150ms p99 budget. It holds to 100 concurrent (128ms) and is
gone by 150 (179ms).

**3. Past that point it keeps accepting work anyway.**

Throughput keeps rising to ~1,600 rps while p99 reaches 256ms. Nothing notices
the budget is gone and nothing sheds load; every caller just waits longer.

So backpressure here is not about preventing failure. It is about honouring a
stated budget instead of quietly missing it for everyone.

**4. The connection pool was never the bottleneck, which is not what I predicted.**

**Tomcat** is the web server inside Spring Boot; it has a pool of threads, 200 by
default, so it can hold 200 requests at once. **HikariCP** is the database
connection pool — 10 by default, so only 10 requests can talk to Postgres
simultaneously; the rest wait.

I expected the pool to be the wall: 200 threads competing for 10 connections,
requests queueing past Hikari's 5-second timeout and surfacing as 500s. It never
happened.

Four rules query the database per decision, about 4ms of database work in total,
so ten connections carry roughly 2,500 requests per second. The load never
reached it.

**5. The batch endpoint is the one real hazard.**

One request of 10,000 events took **34.8 seconds**, held a thread throughout,
returned a 29MB body, and was accepted without complaint. Nothing capped it, so
100,000 events would be a six-minute request.

Per event that is ~287/s against the single endpoint's ~1,500/s: the batch is
sequential on one thread.

---

## Results

### Held concurrency — `knee.js`, 90s per level, database reset between levels

Each row is one 90-second run holding a fixed number of simultaneous callers.
Columns: **VUs** = callers at once. **RPS** = requests finished per second.
**p95 / p99** = the 95th and 99th slowest percentiles of one request.

| VUs | RPS | p95 | p99 | Errors | Within 150ms budget |
|---|---|---|---|---|---|
| 50 | 1,337 | 68ms | 87ms | 0 | yes |
| 75 | 1,450 | 66ms | 101ms | 0 | yes |
| 100 | 1,478 | 86ms | 130ms | 0 | yes |
| 100 | 1,507 | 80ms | **127ms** | 0 | yes |
| 150 | **1,591** | 108ms | 179ms | 0 | no |
| 200 | 1,532 | 170ms | 256ms | 0 | no |

**How to read it.** p99 rises steadily down the column — 87, 101, 128, 179, 256 —
so every caller you add makes the slow requests slower. Throughput stops rising
around 1,500-1,600 rps: past that, extra concurrency buys nothing and costs
latency. The budget of 150ms is crossed between 100 and 150 callers, which is
where the admission limit comes from.

Failures reported by k6 at 150 and 200 (6 and 91 of ~140,000) are a harness
artifact, not a service fault: the sweep stops the application the moment k6
finishes, cutting off requests in flight. The application log contains no
exceptions, and the failed requests are faster than the successful ones —
2.1ms against a 4.7ms floor — which is a connection closing, not a decision
timing out.

**A discarded sample.** A first run at 100 VUs gave 1,126 rps and p99 198ms,
which would have moved the crossover down to between 75 and 100. It showed lower
throughput than both neighbours — not a shape saturation produces — so it was
re-run twice, and both repeats agree with each other and with the trend.

It is recorded here rather than quietly dropped. Single 90-second runs on this
machine carry that much variance, and 75 and 150 were not repeated, so neither
should be quoted tightly either.

### Ramp — `decisions.js`, 10 → 200 VUs over 5m

A **ramp** climbs through concurrency levels in one run rather than holding one:
10 callers, then 50, then 100, then 200. Good for seeing the overall shape, bad
for pinning a number, because its single p99 averages every level together —
which is why the held runs above exist.

371,974 requests, 1,240 rps, zero failures, p99 176ms.

### Batch — `batch.js`

| Events per request | Duration | Status |
|---|---|---|
| 10,000 | 34.8s, 35.2s, 34.8s | 200 |

---

## What this justifies

Each limit is derived from a number above.

| Change | Because |
|---|---|
| `@Size(max = 500)` on the batch list | 10,000 events is a 35-second request. At ~287 events/s, 500 bounds one request to under two seconds |
| Explicit Tomcat and Hikari pool sizes | both are defaults nobody chose; they happen to be adequate, which is luck rather than a decision |
| Admission limit of ~100 in flight, returning **429** | p99 holds at 100 concurrent (128ms) and is gone by 150 (179ms). Beyond it the service exceeds its own budget for every caller rather than turning anyone away |

**429** is the HTTP status for "Too Many Requests" — a deliberate "I am busy, try
again shortly", sent with a `Retry-After` header. It is the opposite of a **500**,
which says "I broke". A system at capacity should say the first; today this one
says neither, and just makes everybody wait.

The third is the one this exercise existed to justify. Before measuring, any
number would have been a guess wearing a decimal point.

---

## Scaling from here

The measured number is 1,500 rps per instance within budget.

| Volume | Average | Peak (~8x) | Instances |
|---|---|---|---|
| 10M/day | 116/s | ~1,000/s | 1 |
| 100M/day | 1,200/s | ~10,000/s | 7-10 |

The application is stateless, so instances are the easy part. Three things break
before it does.

**1. The database, not the application.** Four queries per decision at 10,000 rps
is 40,000 QPS against one Postgres, shared by every instance — so adding
instances makes it worse. Velocity counters belong in Redis with TTL windows;
counting cards seen from an IP in ten minutes is a counter, not a `count(distinct)`
over a growing table. Postgres keeps the audit trail.

**2. Rule count.** Rules run sequentially and half of them query the database. At
fifty rules that is twenty-five round trips per decision and the 128ms p99
becomes seconds. Batch independent queries into one round trip first, then
evaluate in parallel.

**3. Table growth.** `transaction_events` grows without bound and the velocity
windows scan it — this run wrote 372,000 rows in five minutes. Partition by time,
drop old partitions.

### On Kafka

It does not help the authorisation path. That decision is synchronous because an
acquirer is holding the transaction waiting for it; a queue in front turns
"approve or decline" into "we will tell you later", which is not fraud
prevention.

Where it does belong: fan-out after the decision to case management and
analytics, a replayable log for training and backtests, and post-authorisation
monitoring, where chargeback and dispute signals arrive hours later and are
async by nature.

Kafka answers "how do twenty other systems learn what we decided", not "how do
we decide faster".

---

## Method

### The tools

**k6** is the load generator — it sends many requests at once and reports timings.
Scripts are JavaScript, which is why it can build a fresh UUID per request.
`ab` (ApacheBench) cannot, which rules it out here.

A **VU** (virtual user) is one simulated client looping requests back to back, so
"100 VUs" means 100 simultaneous callers, not 100 requests.

### Why every request needs a fresh event id

`POST /decisions` is idempotent on `eventId`. A repeated body takes the replay
path — one indexed lookup, no rule evaluation, no velocity queries. A load test
that reuses identifiers measures a cache and reports it as an engine.

All three scripts generate a UUID per request. That is the reason for k6 rather
than `ab`.

### Why the database is reset between levels

A 90-second run writes over 100,000 events, and the velocity rules scan that
table. Without a reset each level starts against a larger table than the last,
and the numbers measure data growth rather than concurrency.

### Running it

```bash
brew install k6
./mvnw -q -DskipTests package
./perf/knee-sweep.sh 50 75 100 150 200
```

The sweep resets Postgres, starts the application, waits for health, holds the
level for 90 seconds, and stops the application, for each level in turn.

### Caveats to state with any number

Application, Postgres and k6 on one laptop, competing for the same cores. One
instance, no proxy, no warm JIT beyond the first seconds of each run, and one
sample per level except 100, which has three. These are directional: good for finding the failure mode and the
budget crossover, not a capacity figure for a real deployment.
