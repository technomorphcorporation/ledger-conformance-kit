# The suite against Formance Ledger

The first run of this kit against a ledger nobody here wrote.

Every other example in the repository tests something implemented in this tree, where a failing
invariant was put there deliberately. This one runs the same fifteen invariants against
[Formance Ledger](https://github.com/formancehq/ledger) over its real v2 HTTP API, and the
outcome was not known before it started.

## What was run

| | |
|---|---|
| Ledger | Formance Ledger **v2.3.22** |
| Image | `ghcr.io/formancehq/ledger@sha256:e3dc9097…4c50`, pinned by digest |
| Storage | `postgres:16-alpine` |
| Seeds | `42` and `8823714` |
| Adapter | [`FormanceLedgerAdapter`](../lck/src/main/java/com/technomorph/lck/adapter/FormanceLedgerAdapter.java) |

Reproduce with `./gradlew dockerTest --tests '*FormanceConformance*'`, optionally with
`-Plck.seed=…`. It skips rather than fails without Docker.

## Result

**Thirteen invariants held. Two were not applicable. Nothing failed, on either seed.**

| Invariant | Seed 42 | Seed 8823714 |
|---|---|---|
| INV-01 balanced transactions | pass | pass |
| INV-02 journal append-only | pass | pass |
| INV-03 hash chain | *not applicable* | *not applicable* |
| INV-04 idempotent replay | pass | pass |
| INV-05 concurrent duplicate submission | pass (1 of 64 applied, 63 duplicate) | pass |
| INV-06 concurrent credits | pass (500 of 500) | pass |
| INV-07 conservation under transfer | pass (400 of 400) | pass |
| INV-08 read path agrees with journal | pass | pass |
| INV-09 overdraft guard | pass (10 of 20, 10 refused) | pass |
| INV-10 one-subunit transfers | pass (1000 of 1000) | pass |
| INV-11 cross-currency | pass — **but read the caveat below** | pass |
| INV-12 compensation | *not applicable* | *not applicable* |
| INV-13 balances rebuildable | pass | pass |
| INV-14 per-account sequencing | pass | pass |
| INV-15 duplicate of a refused transaction | pass (32 of 32 refused, none reported applied) | pass |

The two seeds produce the same verdicts with different amounts, which is the point of running
both: the workload varied and the answers did not.

INV-05 and INV-15 are worth singling out. Sixty-four simultaneous submissions of one idempotency
key produced exactly one application and sixty-three duplicates, and thirty-two simultaneous
submissions of a key whose transaction could never be funded were all refused with none reported
as already applied. That second one is the defect [RFC 0001](../rfcs/0001-duplicate-of-a-rejected-transaction.md)
exists for, and Formance does not have it.

## The caveat on INV-11, which matters more than the row

**INV-11 reports a pass that Formance did not earn, and would not have earned under any
implementation.** It is recorded here rather than left in the table to be taken at face value.

The invariant submits a deliberately malformed transaction — a USD debit closing a EUR credit,
with no FX leg — and expects the ledger to refuse it. A Formance posting is single-asset and
balanced by construction: source, destination, one amount, one asset. A transaction whose legs do
not balance within a currency **has no representation in that model at all**, so the adapter
cannot send one, and the ledger never gets to decide.

The adapter therefore refuses it locally and says so in the `PostResult` reason. The invariant
sees a rejection and passes. The row is not wrong about the outcome — a client genuinely cannot
book this against Formance — but it is wrong about the mechanism, and a reader who takes it as
"Formance evaluated this and said no" has been misled by the table.

This is a gap in the kit, not in Formance. There is currently no way for an adapter to report
*this ledger's model cannot express the transaction*, which is a third thing distinct from pass
and from fail, and the nearest available answer flatters the ledger. Capability gating solves the
adjacent problem — a feature the ledger lacks — but not this one, where the data model makes the
defect unrepresentable. Tracked as a design question rather than patched with a special case.

## What this run does not show

- **It is not a security review, a performance benchmark, or an audit.** Fifteen correctness
  properties of the money path, under one workload, on one machine.
- **Two invariants were not measured at all.** `HASH_CHAIN` and `COMPENSATION` are not declared by
  this adapter — see the next section. A not-applicable row is not a pass.
- **No fault injection.** Nothing here kills a process, partitions a network, or restarts the
  ledger mid-transaction. Those are v2.0 work; see [ROADMAP.md](../ROADMAP.md).
- **One version.** v2.3.22, at one digest. It says nothing about any other build.

## Capabilities, deliberately under-declared

`OVERDRAFT_GUARD` and `REPLAY` are declared. `HASH_CHAIN` and `COMPENSATION` are not, and both
omissions cost a row of coverage on purpose:

- **`HASH_CHAIN`** — Formance hashes its log, but that chain is not carried on the entries this
  adapter produces. Declaring it would make INV-03 assert against hashes the adapter computed
  itself, which proves nothing about the ledger.
- **`COMPENSATION`** — Formance reverts by booking a compensating transaction and never deletes,
  which looks like a match. "Looks like" is not the standard, and confirming it means reading what
  INV-12 requires an adapter to do rather than assuming.

An undeclared capability is reported as not applicable and never as a failure. Under-declaring
costs coverage; over-declaring costs the credibility of every other row.

## The mapping, and where it could be attacked

The full set of decisions is in the adapter's class javadoc, with the reasoning for each. The
short version:

| Kit | Formance v2 |
|---|---|
| `idempotencyKey` | `Idempotency-Key` request header |
| `PostStatus.DUPLICATE` | `Idempotency-Hit: true` response header |
| `PostStatus.REJECTED` | `INSUFFICIENT_FUND` |
| `allowOverdraft` | `?force=true` — "disable balance checks" |
| `reset()` | `POST /v2/{ledger}` — a **new** ledger each time |

Two are worth stating here because they are the ones a maintainer would reasonably question.

**`reference` is deliberately unused.** Formance has two deduplication mechanisms that do not mean
the same thing: the `Idempotency-Key` header replays the original response, while a reused
`reference` raises `CONFLICT`. Only the first is idempotency in the sense the kit's `DUPLICATE`
means. Running the suite against the `reference` mechanism is a separate question deserving its
own run, not a variation to fold into this one.

**Legs are paired into postings greedily, per currency.** The kit models a transaction as
independent debit and credit legs; Formance models it as directed postings. For the ordinary
two-leg transfer the mapping is exact. For wider transactions more than one valid pairing exists,
and [`FormancePostingsTest`](../lck/src/test/java/com/technomorph/lck/adapter/FormancePostingsTest.java)
asserts the property that makes the choice irrelevant: across eight hundred randomly generated
balanced transactions, every account moves exactly what its legs said. Legs that cannot be paired
are never silently dropped — that was a real bug in the first draft of this adapter, and posting
the paired remainder would have moved different money from the money the caller asked to move.

## Three bugs this run found, all of them ours

Worth recording, because the value of a first run against unfamiliar software is mostly what it
teaches you about your own harness:

1. **`journal()` treated a not-yet-created ledger as an error.** TCK-00 reads the journal before
   anything writes, to refuse a non-scratch environment. A ledger that does not exist is the
   emptiest a ledger can be.
2. **The adapter assumed `reset()` always runs first.** TCK-01 writes, *then* resets, then checks
   the write is gone. The ledger is now created lazily on first use.
3. **Unpairable legs were silently discarded**, which sent an empty transaction rather than
   refusing one.

All three were caught by the TCK and by the run's own assertion that no invariant may end in
`ERROR` — an exception escaping the adapter is a defect in the adapter, never a finding about the
ledger. That assertion is why this page reports a result rather than three false findings.
