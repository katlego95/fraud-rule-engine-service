# ADR 0005 — Data protection: exclusion by design, masking by construction

**Status:** Accepted
**Date:** 2026-08-03

## Context

The strongest data protection decision in this service was made before it existed: PANs never enter it. Card numbers are tokenised upstream and the service receives an opaque `cardToken` — never a real PAN. This is the same category of decision as declaring categorisation upstream or scoping to ZAR: a boundary drawn deliberately and stated, not an omission. Because cardholder data never arrives, the service sits outside PCI-DSS cardholder-data scope by construction. That is a claim about architecture, not a certification — but a scope you never enter is a scope you never have to defend.

What does arrive still matters. `deviceId` and `ipAddress` are personal information under POPIA, so minimisation and purpose limitation apply. The observability requirement forbids logging full card tokens, coordinates or IP addresses at info level — but a prohibition enforced by discipline fails the first time someone adds a log line under pressure.

## Decision

**Card tokens are masked in serialisation, not by convention.** The token is a value type whose JSON serialisation and `toString` both render `tok_****4821` — last four characters only. The unmasked form is unreachable through the normal serialisation path, so the careless log line is removed as an opportunity rather than warned against.

**`deviceId` and `ipAddress` are hashed at rest with a salted SHA-256.** A one-way hash reduces what a database compromise yields, and because it is deterministic the same device always hashes to the same value, so equality survives — which is what grouping and counting need — without the value being readable.

**`cardToken` and `accountId` stay in plaintext.** A deliberate, stated exception: velocity rules query by card token over event-time windows, and the retrieval API filters on both. Hashing them would break the queries the service exists to run. That is a real residual risk accepted for a stated reason, not an oversight.

## Alternatives considered

**Column-level encryption via pgcrypto** — declined for this build. Half-implemented encryption is worse than none: it produces the appearance of protection without key management, rotation, or a defined recovery path. Naming what production would actually require is worth more than a half-built version.

**Hashing `cardToken` and `accountId` too** — declined. This is a design constraint, not laziness: the velocity windows and retrieval filters are the service's purpose, and they require the plaintext values they index.

**Masking at the logging layer only** — an appender pattern or MDC convention. Declined for the same reason as the serialisation decision: it depends on every future log line honouring the convention, and it leaves API responses unmasked entirely.

## Consequences

Production would add: column-level encryption or an encrypted volume; keys held in a managed KMS rather than application configuration; and a defined rotation schedule. The hashing salt is itself configuration that must be managed as a secret, and it cannot be rotated without invalidating every existing hash.

The salt is not decoration. Hashing is deterministic and the input spaces are small — IPv4 is only 2^32 values — so an unsalted hash falls to a dictionary attack. The salt is the mitigation, which is exactly why it must be held as a secret.

The audit trail pays a real cost: a masked token in the audit response means an investigator cannot read the full token from the API and must join through the account or the event identifier instead.

Under POPIA, holding device and IP values hashed, unlogged, and unread by any rule is minimisation made concrete: they are retained only for correlation, which is the stated purpose.


---

## Update — 2026-09-08

The original wording justified hashing partly on the grounds that "no query filters on them and no rule reads them". That is no longer true: `IP_CARD_SPREAD` and `DEVICE_ACCOUNT_SPREAD` (ADR 0008) both count distinct identifiers over these columns.

The decision stands, and the better justification is the one that survives: a deterministic hash preserves equality, so grouping and counting work unchanged. What it costs is **structure** — a hash cannot be matched by IP subnet, prefix or ASN, only by exact equality. Any future rule needing that shape would have to revisit this.

Two consequences worth recording:

- Evaluators receive the **raw** event and the columns hold fingerprints, so a rule must hash before it queries. Getting that wrong returns zero and reads as a clean transaction — a rule that never fires and never errors. `EventPrivacy.fingerprint` is public for exactly this reason, and `SpreadEvaluatorIT.sixCardsFromOneIpMatches` is the regression test.
- The card token remains plaintext, and the reason stated here — that hashing would break the velocity queries — is the weakest form of the argument, since the spread rules prove equality lookups work fine against hashed columns. The stronger reasons are that the masked form needs the last four digits, and that a card token is already tokenised: a stolen table yields tokens, never PANs.
