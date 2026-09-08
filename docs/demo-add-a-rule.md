# Adding rules without a deployment

Five new rules, each of a different existing type, created over HTTP against a
running service. No code, no restart, no migration.

The boundary this demonstrates is in ADR 0007: **configuration is free,
extension is cheap but not free.** A new rule of an existing type is a POST. A
new *kind* of rule needs an evaluator, and that needs a branch.

```bash
BASE=http://localhost:8080/api/v1
```

Start the service with the demo profile so there is traffic to decide against:

```bash
SPRING_PROFILES_ACTIVE=demo ./mvnw spring-boot:run
```

---

## The five rules

Each is `SHADOW` and `CONTRIBUTORY`. Shadow because ADR 0008 says a threshold
nobody has measured does not get to decline anyone; contributory because one
signal on its own is evidence, not proof.

### 1. Tiering an existing threshold — `AMOUNT_THRESHOLD`

`HIGH_AMOUNT` fires above R10,000 for 15 points. A R50,000 transaction is a
louder signal than a R10,001 one, and a single threshold cannot say so.

```bash
curl -s -X POST $BASE/rules -H 'Content-Type: application/json' -d '{
  "code": "VERY_HIGH_AMOUNT",
  "type": "AMOUNT_THRESHOLD",
  "mode": "SHADOW",
  "nature": "CONTRIBUTORY",
  "weight": 30,
  "parameters": "{\"threshold\":\"50000.00\"}",
  "description": "Transaction amount above R50,000.",
  "typology": "General anomaly, high value"
}'
```

### 2. A second category set — `MCC_SET`

`HIGH_RISK_MCC` covers betting and quasi-cash. This one covers categories used
to turn a stolen card into something that holds value.

```bash
curl -s -X POST $BASE/rules -H 'Content-Type: application/json' -d '{
  "code": "VALUE_TRANSFER_MCC",
  "type": "MCC_SET",
  "mode": "SHADOW",
  "nature": "CONTRIBUTORY",
  "weight": 20,
  "parameters": "{\"codes\":[\"5094\",\"6540\",\"4829\"]}",
  "description": "Precious metals and stones, stored-value top-up, or money transfer.",
  "typology": "Rapid liquidation of a compromised card"
}'
```

### 3. Same evaluator, opposite policy — `COUNTRY_BLOCKLIST`

`BLOCKED_COUNTRY` is decisive: a sanctions hit blocks outright. This one uses
the same evaluator to express something weaker — elevated risk that should
count towards a score, not end the transaction.

The list here is illustrative. In production it comes from a compliance feed,
not a hardcoded array.

```bash
curl -s -X POST $BASE/rules -H 'Content-Type: application/json' -d '{
  "code": "ELEVATED_RISK_COUNTRY",
  "type": "COUNTRY_BLOCKLIST",
  "mode": "SHADOW",
  "nature": "CONTRIBUTORY",
  "weight": 15,
  "parameters": "{\"countries\":[\"NG\",\"PK\",\"MM\"]}",
  "description": "Merchant country on the elevated-risk list.",
  "typology": "Elevated-risk jurisdiction"
}'
```

### 4. Same evaluator, different channel — `CHANNEL_AMOUNT`

`CNP_HIGH_AMOUNT` watches e-commerce. Cash withdrawal is the other end of a
takeover: the point at which the money leaves.

Weight 25 is chosen so that this rule plus `HIGH_AMOUNT` reaches exactly 40,
the review band. Neither alone is enough; together they are.

```bash
curl -s -X POST $BASE/rules -H 'Content-Type: application/json' -d '{
  "code": "ATM_HIGH_WITHDRAWAL",
  "type": "CHANNEL_AMOUNT",
  "mode": "SHADOW",
  "nature": "CONTRIBUTORY",
  "weight": 25,
  "parameters": "{\"channel\":\"ATM\",\"threshold\":\"3000.00\"}",
  "description": "Cash withdrawal above R3,000.",
  "typology": "Account takeover, cash-out"
}'
```

### 5. A tighter threshold, tested safely — `CARD_COUNT_VELOCITY`

`CARD_TXN_VELOCITY` blocks at more than 4 authorisations in 5 minutes. Is 2 in
2 minutes better? Nobody knows. In shadow the question is answerable without
declining anyone to find out.

```bash
curl -s -X POST $BASE/rules -H 'Content-Type: application/json' -d '{
  "code": "CARD_BURST_TIGHT",
  "type": "CARD_COUNT_VELOCITY",
  "mode": "SHADOW",
  "nature": "CONTRIBUTORY",
  "weight": 25,
  "parameters": "{\"maxCount\":2,\"windowMinutes\":2}",
  "description": "More than 2 authorisations on one card within 2 minutes.",
  "typology": "Card testing, tighter threshold under evaluation"
}'
```

Confirm all five are live — thirteen rules, no restart:

```bash
curl -s $BASE/rules | jq -r '.[] | "\(.code)  \(.mode)  v\(.version)"'
```

---

## What shadow actually buys

Send a R12,000 ATM withdrawal:

```bash
curl -s -X POST $BASE/decisions -H 'Content-Type: application/json' -d '{
  "eventId": "11111111-1111-4111-8111-111111111111",
  "occurredAt": "2026-08-03T09:00:00Z",
  "accountId": "acct-demo",
  "cardToken": "4000123412341234",
  "amount": "12000.00",
  "currency": "ZAR",
  "merchantId": "atm-sandton",
  "merchantCategoryCode": "6011",
  "merchantCountry": "ZA",
  "channel": "ATM"
}' | jq '{verdict, totalScore}'
```

`APPROVE`, score 15 — only `HIGH_AMOUNT` counted.

Now look at what the shadow rule recorded anyway:

```bash
curl -s $BASE/decisions?eventId=11111111-1111-4111-8111-111111111111 \
  | jq '.outcomes[] | select(.ruleCode=="ATM_HIGH_WITHDRAWAL")'
```

`MATCHED`, `contribution: 25`, `mode: SHADOW`. The rule ran, said what it would
have added, and changed nothing.

## Promoting it

```bash
RULE_ID=$(curl -s $BASE/rules | jq -r '.[] | select(.code=="ATM_HIGH_WITHDRAWAL") | .id')

curl -s -X PATCH $BASE/rules/$RULE_ID/mode \
  -H 'Content-Type: application/json' -d '{"mode":"ACTIVE"}'
```

Send the same transaction with a new event identifier — the old one replays,
which is the idempotency guarantee working:

```bash
curl -s -X POST $BASE/decisions -H 'Content-Type: application/json' -d '{
  "eventId": "22222222-2222-4222-8222-222222222222",
  "occurredAt": "2026-08-03T09:05:00Z",
  "accountId": "acct-demo",
  "cardToken": "4000123412341234",
  "amount": "12000.00",
  "currency": "ZAR",
  "merchantId": "atm-sandton",
  "merchantCategoryCode": "6011",
  "merchantCountry": "ZA",
  "channel": "ATM"
}' | jq '{verdict, totalScore}'
```

`REVIEW`, score 40. Same transaction, different outcome, and nothing was
deployed between the two calls.

## Undoing it

```bash
curl -s -X PATCH $BASE/rules/$RULE_ID/mode \
  -H 'Content-Type: application/json' -d '{"mode":"DISABLED"}'
```

The rule leaves the evaluation set but stays in the table, and every decision it
already influenced still names the version that fired.

---

## What this does not show

A new *kind* of rule. All five reuse an evaluator that already exists; nothing
here could add a rule that reads a field the engine has never looked at. That
takes a `RuleType` value, an evaluator, a query and a migration — the work in
the IP and device typology branch.

Knowing which of the two a request is asking for is most of the value of this
distinction.
