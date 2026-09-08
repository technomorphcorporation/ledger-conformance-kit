# Three reference architectures

Each one is a design I have had to get right on a real programme. The diagrams are
deliberately boring; the value is in the annotations about what breaks.

---

## A. High-concurrency wallet and merchant ledger

The case: flash sales, payout runs, marketplace settlement. A handful of accounts
take a disproportionate share of the write volume.

```
  client ──▶ API gateway ──────────────────────────────────────────┐
                │  Idempotency-Key required                        │
                │  Redis SETNX (fast path only, never the guarantee)│
                ▼                                                   │
        posting service                                             │
                │  validate: legs net to zero per currency          │
                │  classify: is any leg a GUARDED account?          │
        ┌───────┴────────┐                                          │
        ▼                ▼                                          │
   GUARDED path     ACCRUAL path                                     │
   partitioned      shard fan-out                                    │
   by account_id    (16–64 rows)                                     │
        │                │                                          │
        └───────┬────────┘                                          │
                ▼                                                   │
     ┌─────────────────────────────────────┐                        │
     │  single DB transaction               │                        │
     │   1. INSERT idempotency_keys  ◀──────┼── unique index = the guarantee
     │   2. INSERT journal_entries (append)  │                        │
     │   3. UPDATE balance shard(s)          │                        │
     │   4. INSERT outbox                    │                        │
     └─────────────────────────────────────┘                        │
                │                                                    │
                ▼                                                    ▼
       outbox relay ──▶ Kafka (key = account_id) ──▶ projections, analytics,
                                                     notification, recon
```

**The one non-obvious decision.** Guarded and accrual accounts take different paths.
Everything else in this diagram is standard; that split is what makes both `INV-06`
and `INV-09` pass at once.

**Schema.**

```sql
-- The guarantee. Not the cache.
CREATE TABLE idempotency_keys (
  idempotency_key   TEXT PRIMARY KEY,
  request_hash      BYTEA       NOT NULL,
  transaction_id    UUID        NOT NULL,
  state             TEXT        NOT NULL CHECK (state IN ('IN_FLIGHT','COMMITTED','FAILED')),
  response_body     JSONB,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE journal_entries (
  entry_id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  transaction_id    UUID        NOT NULL,
  account_id        UUID        NOT NULL,
  entry_type        TEXT        NOT NULL CHECK (entry_type IN ('DEBIT','CREDIT')),
  amount_subunits   BIGINT      NOT NULL CHECK (amount_subunits > 0),
  currency          CHAR(3)     NOT NULL,
  account_sequence  BIGINT      NOT NULL,
  prev_hash         BYTEA       NOT NULL,
  entry_hash        BYTEA       NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
) PARTITION BY RANGE (created_at);

-- Two constraints do most of the work:
CREATE UNIQUE INDEX ON journal_entries (account_id, account_sequence);  -- ordering
REVOKE UPDATE, DELETE ON journal_entries FROM app_role;                 -- immutability

CREATE TABLE balance_shards (
  account_id        UUID   NOT NULL,
  shard_id          SMALLINT NOT NULL,
  currency          CHAR(3) NOT NULL,
  balance_subunits  BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (account_id, currency, shard_id)
);
```

**The guarded spend, in one statement.** No `SELECT` first — the predicate and the
write are the same operation, so there is no window to lose:

```sql
UPDATE balance_shards
   SET balance_subunits = balance_subunits - :amount
 WHERE account_id = :acct AND currency = :ccy AND shard_id = 0
   AND balance_subunits >= :amount
RETURNING balance_subunits;
-- zero rows returned == insufficient funds. Not an exception; a business outcome.
```

**Spring/JDBC, the idempotency claim.** `ON CONFLICT DO NOTHING` returning zero rows
is the atomic "someone else already claimed this":

```java
int claimed = jdbc.update("""
    INSERT INTO idempotency_keys (idempotency_key, request_hash, transaction_id, state)
    VALUES (?, ?, ?, 'IN_FLIGHT')
    ON CONFLICT (idempotency_key) DO NOTHING
    """, key, requestHash, txnId);

if (claimed == 0) {
    var existing = repo.find(key);
    if (existing.state() == COMMITTED) return existing.response();   // replay the original
    if (!existing.requestHash().equals(requestHash)) throw new PayloadMismatch();
    throw new InFlight();          // 409, client retries with backoff. Never a fake 200.
                                   // INV-15 fails a ledger that returns 200 here.
}
```

**Sizing the shards.** Start at 16. More shards means more rows summed on every
read; fewer means contention returns. The signal to add shards is `UPDATE` wait time
on the shard rows, not transaction volume.

---

## B. Multi-custodian position and cash reconciliation

The case: a brokerage, robo-advisor or treasury desk holding positions at several
custodians. Internal position must equal custodian position, per account, per
instrument, every day, and the difference must be explainable.

```
  custodian A file ──┐
  custodian B API  ──┼──▶ normaliser ──▶ external_positions (as-of, source, hash)
  custodian C SWIFT ─┘        │                    │
                              │                    ▼
   internal journal ──▶ position projection ──▶ MATCHER ──▶ breaks
                                                    │          │
                                     match keys:    │          ▼
                                     account+instrument+as_of   break lifecycle
                                     amount tolerance = 0       (open/assigned/
                                     date tolerance = T+1       aged/resolved)
                                                               │
                                                               ▼
                                               resolving journal entry, linked
```

**What makes this hard, and what to do about it.**

- **Timing, not error.** Most breaks are settlement-date differences, not mistakes.
  Match on trade date *and* settlement date and classify the difference type before
  anyone looks at it. A recon that cannot distinguish "pending settlement" from
  "wrong" is noise, and noise gets muted.
- **Corporate actions.** A stock split changes quantity with no transaction. Model
  corporate actions as first-class journal events, or every one of them shows up as
  a break.
- **Idempotent file ingestion.** Custodians resend files. Hash the file, hash each
  row, and make ingestion a no-op on redelivery. This is the same `INV-04` problem
  wearing a different hat.
- **Tolerance is zero on quantity, non-zero on cash.** Fractional-share rounding and
  fee accruals produce legitimate small cash differences. Quantity differences never
  are. Different thresholds, different escalation.
- **The output is one number.** Unexplained difference by age bucket, per custodian.
  Everything else is a drill-down.

---

## C. Regulatory reporting off the same journal

The case: short-sell marking, position-limit monitoring, 871(m) withholding,
transaction reporting. The report has to be defensible two years later.

```
   journal (immutable, hash-chained)
        │
        ▼
   as-of projection ──▶ rule engine ──▶ report artifact ──▶ submission
        │                   │                  │
        │             versioned rules     content hash + rule version
        │             (effective-dated)   stored together
        ▼                                      │
   replay any as-of date ◀────────────────────┘
   with the rule version that applied then
```

**The property everything else hangs on:** you can regenerate the exact report you
filed on any past date, byte for byte, and show which rule version produced it.
That requires three things and teams usually have one:

1. Immutable, ordered source events (the journal).
2. **Effective-dated rules**, versioned and stored — not code deployed and forgotten.
3. The submitted artifact's hash stored next to the inputs and the rule version.

**Monitoring vs reporting are different systems.** Real-time position-limit
monitoring reads a streaming projection and optimises for latency; regulatory
reporting reads a settled as-of projection and optimises for reproducibility.
Serving both from one path gives you a slow monitor and an unreproducible report.
Split them, and reconcile the two projections against each other daily — that
reconciliation is itself one of the more valuable controls in the stack.

---

## Where these three meet

All three are the same architecture seen from different ends: an immutable ordered
log of money movements, plus projections that must be provably derivable from it.
Get the log right and the wallet, the recon and the regulator are all downstream
problems. Get it wrong and every one of them becomes a permanent manual process.
