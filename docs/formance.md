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

Against a Formance instance you are already running, the CLI reaches it directly:

```bash
lck tck --adapter formance:http://localhost:3068
lck run --adapter formance:http://localhost:3068
```

The `formance:` prefix matters. A bare `http://` URL selects the universal adapter, which expects
four `/_lck/` endpoints mounted inside your own service, and will answer a Formance endpoint with
a wall of 404s that looks like a broken ledger rather than the wrong adapter. `--capabilities` is
neither needed nor consulted here: this adapter declares its own.

## Result

**Twelve invariants held. Three were not applicable. Nothing failed, on either seed.**

> **Corrected on 18 September 2026.** This page first reported fourteen held and one not
> applicable. Two of those fourteen were passes Formance had not earned, and only one of them was
> flagged. The adapter now reports a transaction the ledger cannot express as unmeasured rather
> than refusing it itself, and INV-01 turned out to be in that category as well as INV-11 — which
> the original write-up missed. No verdict got worse: two passes became not-applicable rows, and
> nothing became a failure. The count is lower and the page is now true.

| Invariant | Seed 42 | Seed 8823714 |
|---|---|---|
| INV-01 balanced transactions | *not applicable* | *not applicable* |
| INV-02 journal append-only | pass | pass |
| INV-03 hash chain | *not applicable* | *not applicable* |
| INV-04 idempotent replay | pass | pass |
| INV-05 concurrent duplicate submission | pass (1 of 64 applied, 63 duplicate) | pass |
| INV-06 concurrent credits | pass (500 of 500) | pass |
| INV-07 conservation under transfer | pass (400 of 400) | pass |
| INV-08 read path agrees with journal | pass | pass |
| INV-09 overdraft guard | pass (10 of 20, 10 refused) | pass |
| INV-10 one-subunit transfers | pass (1000 of 1000) | pass |
| INV-11 cross-currency | *not applicable* | *not applicable* |
| INV-12 compensation | pass (4 original entries intact, 2 appended) | pass |
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

## Why two rows are unmeasured rather than passed

**Formance cannot express either transaction**, so it was never asked and has no opinion to
report. That is now what the rows say. Until 18 September 2026 they said *pass*, which was true
about the outcome and false about the mechanism.

The invariant submits a deliberately malformed transaction — a USD debit closing a EUR credit,
with no FX leg — and expects the ledger to refuse it. A Formance posting is single-asset and
balanced by construction: source, destination, one amount, one asset. A transaction whose legs do
not balance within a currency **has no representation in that model at all**, so the adapter
cannot send one, and the ledger never gets to decide.

INV-01 is the same shape: it submits a transaction debiting more than it credits, and a Formance
posting has one amount, so there is nothing to send there either.

The adapter used to refuse both locally and return a rejection, which the invariants read as the
ledger having refused. It now throws `NotRepresentable`, and the runner reports the invariant as
not applicable with the adapter's reason attached. Neither row is a pass, and neither is a
failure: the question could not be put.

Both outcomes remain correct — money cannot be created either way in Formance, structurally. What
changed is that the report no longer claims Formance demonstrated that.

## What this run does not show

- **It is not a security review, a performance benchmark, or an audit.** Fifteen correctness
  properties of the money path, under one workload, on one machine.
- **Three invariants were not measured.** `HASH_CHAIN` is not declared by this adapter — see
  the next section. A not-applicable row is not a pass.
- **No fault injection.** Nothing here kills a process, partitions a network, or restarts the
  ledger mid-transaction. Those are v2.0 work; see [ROADMAP.md](../ROADMAP.md).
- **One version.** v2.3.22, at one digest. It says nothing about any other build.

## Capabilities, deliberately under-declared

`OVERDRAFT_GUARD`, `REPLAY` and `COMPENSATION` are declared. `HASH_CHAIN` is not.

`COMPENSATION` was withheld in the first draft of this adapter on the assumption that INV-12 might
need Formance's `/revert` endpoint. Reading the invariant rather than assuming showed it needs
nothing special: it posts an ordinary reversing transfer, then requires the journal to have grown,
the original entries to survive as an unmodified ordered prefix, and the net position to return to
zero. Formance appends and never rewrites, so all three follow from how it already works — and
TCK-08, which checks that declared capabilities match observable behaviour, agrees.

**`HASH_CHAIN`** stays undeclared. Formance hashes its log, but that chain is not carried on
the entries this adapter produces. Declaring it would make INV-03 assert against hashes the
adapter computed itself, which proves nothing about the ledger.

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

## Four bugs this run found, all of them ours

Worth recording, because the value of a first run against unfamiliar software is mostly what it
teaches you about your own harness:

1. **`journal()` treated a not-yet-created ledger as an error.** TCK-00 reads the journal before
   anything writes, to refuse a non-scratch environment. A ledger that does not exist is the
   emptiest a ledger can be.
2. **The adapter assumed `reset()` always runs first.** TCK-01 writes, *then* resets, then checks
   the write is gone. The ledger is now created lazily on first use.
3. **Unpairable legs were silently discarded**, which sent an empty transaction rather than
   refusing one.
4. **Two consecutive runs against the same server collided.** Ledger names restarted from the same
   point each time, so the second run opened on the first run's data and TCK-00 refused to
   continue — correctly, and for a reason that reads as a broken ledger rather than a name
   clash. Names now carry a per-run token.

All four were caught by the TCK and by the run's own assertion that no invariant may end in
`ERROR` — an exception escaping the adapter is a defect in the adapter, never a finding about the
ledger. That assertion is why this page reports a result rather than three false findings.
