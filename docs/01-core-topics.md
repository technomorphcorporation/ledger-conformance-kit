# The six things that decide whether a money system is correct

Study notes behind the conformance kit. Each section states the failure first,
because the failure is what a buyer recognises. The mechanics come second.

---

## 1. Concurrency: the hot account

**The failure.** Two requests read the same balance, both decide it is sufficient,
both write. One of the writes is lost, or the account goes negative. It happens on
exactly one account — the busiest one — which is also the one carrying the most money.

**Why the obvious fixes are wrong.**

| Approach | Correct? | Throughput | Fails when |
|---|---|---|---|
| `SELECT` then `UPDATE` in app code | No | High | Always, under any real concurrency |
| `SELECT ... FOR UPDATE` on the balance row | Yes | ~1 txn per row per RTT | The row is hot; queue depth explodes |
| Optimistic version column + retry | Yes | Good when contention is low | Contention is high — you retry-storm |
| `UPDATE balance = balance - x WHERE balance >= x` | Yes | Good | You need multi-leg atomicity or a richer predicate |
| Balance sharded across N rows | Yes for accrual | Very high | You need a spend guard — see below |
| Single-writer per account (partitioned queue) | Yes | High, and back-pressures cleanly | You need cross-account atomicity in one hop |

**The distinction most designs miss.** Sharding a balance across N rows removes
lock contention, but it also removes your ability to evaluate a predicate over the
whole balance. You cannot ask "are there sufficient funds" when you can only see
one-sixteenth of the account. So:

- **Accrual-only accounts** (merchant settlement, omnibus, funding, fee pools) —
  shard freely. `SUM(balance_subunits)` on read, N-way `UPDATE` on write.
- **Guarded accounts** (customer wallets, anything with an overdraft or limit check)
  — keep one serialization point. Either a single row with a conditional `UPDATE`,
  or a partitioned queue where one consumer owns the account.
- **Hybrid** — shard the account and hold a separate *reserved* pool. Spend goes
  through a reservation with a TTL; the reservation, not the balance, is the
  contended resource, and it is small and short-lived.

`INV-06` and `INV-09` in the kit are exactly this pair. A design that passes one
by breaking the other is the most common thing I find.

**Lock ordering.** Any transaction touching more than one account must acquire
locks in a total order (account id is fine). Without it you have a deadlock that
appears only under production interleaving, and your on-call learns about it at
month-end.

---

## 2. Idempotency: the retry you did not write

**The failure.** The client times out at 30s. Your service committed at 31s. The
client retries. You charge twice. Nobody wrote a bug — the network did.

**Four layers, and you need at least two.**

1. **Client-supplied key.** `Idempotency-Key` header, generated once per logical
   intent, reused across retries. If the client generates a fresh key per attempt,
   nothing downstream can save you.
2. **Gateway cache.** Redis `SET key value NX PX ttl`. Fast rejection of the
   obvious duplicate. This is an optimisation, not a guarantee — Redis can lose it.
3. **Database uniqueness.** A `UNIQUE` constraint on `idempotency_key` in the same
   transaction as the ledger write. This is the guarantee. Everything above it is
   latency reduction.
4. **Deterministic derived keys.** For system-generated movements (settlement runs,
   interest accrual, sweeps), derive the key from the business facts —
   `sha256(run_date|account|type|amount)` — so a rerun is inherently a no-op.

**The three-state rule.** An idempotency record has three states, and collapsing
them to two is where the bugs live:

- `IN_FLIGHT` — claimed, not yet committed. A concurrent duplicate must **wait or
  be told to retry**, not be told "success" (you would return a result that does
  not exist yet) and not be allowed to proceed.
- `COMMITTED` — return the stored original response, byte for byte.
- `FAILED` — release the claim so a genuine retry can succeed.

**Payload binding.** Store a hash of the request body with the key. Same key,
different body is a client bug, and it should be a `422`, not a silent replay of a
different amount.

`INV-04` and `INV-05` cover the sequential and concurrent cases, and `INV-15` covers the
three-state rule directly: it fires simultaneous duplicates of a transaction that will be
*refused*, and fails a ledger that answers any of them with "already applied". That is the case
the other two cannot see, because both fund the account first, so the transaction under test
always succeeds.

Worth knowing where INV-15 came from: this kit's own reference ledger collapsed IN_FLIGHT and
COMMITTED, and all fourteen invariants passed it. The defect was found by reading, and the
invariant was written afterwards. Systems that pass
the first and fail the second are common: the check exists, it just is not atomic
with the write.

---

## 3. Event sourcing and CQRS: the journal is the system

**The rule.** The append-only journal is the only source of truth. Balances,
statements, dashboards, regulatory extracts are all *projections*, and every one of
them must be reproducible from the journal alone.

**What this buys you, concretely:**

- Point-in-time balances ("what did this account hold at 14:02 on the 3rd") become
  a fold over a prefix, not an archaeology project.
- A bad projection is a rebuild, not a data-loss incident.
- The auditor's question — "prove this row was not edited" — is answerable if you
  hash-chain the journal (`prev_hash`, `entry_hash`). Cheap to add, impossible to
  retrofit onto history you already lost.

**What it costs, and the honest mitigations:**

| Cost | Mitigation |
|---|---|
| Replay time grows without bound | Periodic snapshots; replay from snapshot + tail |
| Projections lag the write path | Publish the lag as a first-class metric with an SLO; make the read API state its as-of sequence |
| Schema evolution on old events | Version every event; upcast on read; never rewrite stored events |
| Storage growth | Partition by month, cold-tier the old partitions, never delete |

**Where teams go wrong.** They keep the event log *and* treat the balance table as
authoritative, updating both. Now there are two truths and no procedure for when
they differ. `INV-08` and `INV-13` exist to catch exactly that drift.

---

## 4. Distributed transactions: sagas and compensation

**The failure.** Auth succeeded, ledger posted, settlement service is down. There is
no rollback across service boundaries, so the money is now in a state that exists in
your ledger and nowhere else.

**Orchestration over choreography, for money.** Choreographed sagas (services
reacting to each other's events) are elegant and unauditable — nobody owns the
overall state. Use a central orchestrator with a durable state machine
(Temporal, Step Functions, or a state table plus a poller). When the regulator asks
what happened to transaction X, you need one place that knows.

**Compensation, not rollback.**

- A compensating entry is a *new* pair of journal entries that reverses the economic
  effect. You never delete or update the original. Both are visible forever.
- Compensations must themselves be idempotent, and they must be retried until they
  succeed — a failed compensation is a manual-intervention alert, not a log line.
- Some steps cannot be compensated (an outbound wire that settled). Those steps go
  **last** in the saga, after everything reversible has committed. Ordering the saga
  by reversibility is the single highest-value design decision in the pattern.

**The transactional outbox.** Never write to the database and publish to Kafka in
the same logical step — one of them will succeed alone. Write the event to an
`outbox` table inside the same database transaction, and have a relay publish it.
At-least-once delivery plus idempotent consumers gives you effectively-once, which
is the strongest thing that actually exists.

`INV-12` checks that a reversal leaves both sides of the story in the journal.

---

## 5. Money arithmetic

**The rules, in order of how much money they have cost people:**

1. **Integers only, in the currency's minor unit.** No float, anywhere, ever, at any
   layer — not in JSON, not in the ORM, not in the analytics export. `INV-10` shows
   the drift: a thousand one-cent transfers, and the destination holds 999.
2. **Carry the currency with the amount.** An amount without a currency is not a
   number, it is a bug waiting for a second market.
3. **Minor units are not always two decimal places.** JPY has 0, KWD and BHD have 3,
   and some crypto rails need 18. Store the exponent per currency; do not hardcode 100.
4. **Allocation must be lossless.** Splitting 100 across 3 ways is 34/33/33, not
   33.33 three times. Use a largest-remainder allocator and assert that the parts
   sum to the whole. Every fee split, tax split and revenue share needs this.
5. **FX is two transactions, not one.** A currency conversion is a debit in one
   currency and a credit in another, joined by an explicit FX position account that
   absorbs the rate. `INV-11` rejects the version where the two currencies simply
   close each other and the difference vanishes.
6. **Rounding is a policy, not a default.** Half-even for statistics, half-up for
   consumer-facing charges in most jurisdictions, always-down for anything you keep.
   Write it down; it will be asked about in an audit.

---

## 6. Reconciliation: the control that actually catches the others

Everything above is prevention. Reconciliation is detection, and detection is what
you are graded on when prevention fails.

**Three loops, running on different clocks:**

| Loop | Frequency | Compares | Detects |
|---|---|---|---|
| Internal invariant | Continuous | Cached balance vs journal projection | Lost updates, projection drift |
| Cross-service | Minutes | Ledger vs payment processor / custodian | Stuck sagas, missed callbacks |
| External statement | Daily | Ledger vs bank statement / custodian file | Fees, returns, unbooked movements |

**Design notes that matter more than the loops themselves:**

- Every break gets an **identity and a lifecycle** — open, assigned, aged, resolved,
  with the resolving journal entry linked. A break log that is just a report gets ignored.
- Age breaks and alarm on age, not on count. Ten breaks that clear same-day are
  healthy; one break open for nine days is an incident.
- **Suspense accounts are part of the ledger, not a spreadsheet.** Money you cannot
  yet classify still has to be double-entered somewhere, or your books do not tie.
- Publish a single number: *unexplained difference, in cents, by age bucket*. That is
  the metric a CFO and a regulator both understand, and it is the number I would put
  on the wall.

---

## The short version, for a design review

Ask these six questions. If a team cannot answer all six with a specific mechanism,
the correctness gap is real and measurable — which is what the kit measures.

1. Which accounts are hot, and what is the serialization point for each?
2. Where does the idempotency guarantee live — cache, or a database constraint?
3. Can you rebuild every balance from the journal alone, and have you tried?
4. Which saga steps are irreversible, and are they ordered last?
5. What is the type of a monetary amount at every layer, end to end?
6. What is your unexplained difference right now, in cents, by age?
