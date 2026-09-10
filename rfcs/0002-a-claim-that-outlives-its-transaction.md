# RFC 0002: A claim that outlives its transaction

- **Status:** draft
- **Proposed invariant:** INV-16
- **Severity:** BLOCKER
- **Capability required:** OVERDRAFT_GUARD

Raised by [Sameer Sood](https://www.linkedin.com/in/sameersood/) in review of the published
material. See `REVIEWERS.md`.

## The failure

A payment is claimed and never settled. The service writes `IN_FLIGHT` against the idempotency
key, commits that, and then dies — a deployment, an OOM kill, a network partition to the card
network it was about to call. Nothing else happens.

Every retry from that point sees a claim in progress and is told to come back later. It comes
back later and is told the same thing. The key is now unusable, permanently, and the payment
behind it will never be made. There is no error anywhere: the ledger is behaving exactly as
designed, refusing to answer for a transaction whose outcome it does not know, and it will
refuse forever because nothing is ever going to tell it.

The customer's money does not move. The operation is bricked until a human notices, and nobody
is looking, because from every dashboard's point of view the request was handled.

## What this invariant does not test

**It does not kill anything.** The suite drives a ledger through five methods; it cannot stop
the process, partition its network or fail its disk. Inducing the crash needs fault injection,
which is deliberately v2.0 — see `ROADMAP.md`.

Writing a check that claimed otherwise would repeat a mistake this corpus has already made
once: mutant M-05 was named "amounts round-trip through double" and did something else entirely,
so what it proved was not what it said. An invariant is worth having only if the thing it
demonstrates is the thing it is named after.

So this tests the **property a correct recovery must produce**, which is observable through the
adapter, rather than the crash that makes recovery necessary. A ledger can satisfy it with a
lease and a sweeper, with a reconciliation job, with an operator runbook, or by never stranding
a claim in the first place — the invariant has no opinion, and should not.

## Why existing invariants do not catch it

`INV-04` and `INV-05` submit duplicates of a transaction that succeeds; the key is consumed
legitimately and never needs releasing. `INV-15` checks what a *concurrent* duplicate is told
while the original is being refused — the answer must not be "already applied" — and stops
there. None of the three asks whether the key still works afterwards.

That gap is not theoretical. A ledger can answer every one of those three correctly and still
burn the key: refuse the transaction, correctly report REJECTED to everyone waiting, and then
never release the claim. It passes fifteen invariants and has permanently destroyed the
customer's ability to retry.

`ReferenceLedgerTest` covers this for the bundled reference implementation, which is how the
gap was noticed. That test is not part of the suite a client runs.

## The check

Fund an account for one transfer. Post a transaction that must be refused — an amount the
account cannot cover, guard engaged — so the ledger claims the key and then fails. Fund the
account properly. Retry the identical key with an amount that will now succeed.

It must apply. A key that never took effect is a key that was never used.

```
the key 'stranded' was claimed by a transaction that was refused, and 35.4s later it is
still unusable against a declared recovery window of 30s — the claim outlived the
transaction it belonged to, and the caller has no way to make this payment
```

The failing message reports how long it waited, because that is the number a reader needs:
it separates "released slowly" from "never released".

## How long to wait: declared, not configured

A correct ledger may hold a claim for a bounded lease before deciding it is stale, and a lease
is a legitimate design — the review that produced this RFC made the point that a sweeper is
*required*, and sweepers run on schedules. So the check cannot demand instant release without
failing correct ledgers whose recovery is merely not instantaneous.

The first draft of this RFC proposed a fixed five-second budget. That was wrong, and the
reviewer's objection to it is the reason: a product should be flexible and lean, and a client
should not have to reason about a tool's internal timeouts. A `--retry-budget` flag would be
the worst of both — a knob whose correct value the client cannot know without understanding how
the check works, on a tool whose whole point is that they should not have to.

**The adapter should declare its recovery window, and the suite should adapt.** That is not a
new idea here; it is exactly what `Capability` already does. An invariant needing a capability
the adapter has not declared reports not applicable, never a failure, because the kit has
opinions about correctness and none about your feature set. A recovery window is the same
shape: a fact about the ledger that only the ledger knows.

```java
/** How long this ledger may take to release a claim whose transaction never applied. */
default Duration claimRecoveryWindow() { return Duration.ZERO; }
```

- **Not declared** (the default) — INV-16 reports **not applicable**. A ledger that has not
  thought about stranded claims is not told it is broken; it is told this was not measured.
  Discovering the property exists is the value, and a skipped row with a reason does that.
- **`Duration.ZERO`** declared deliberately means the release is synchronous, and the check
  expects the very next attempt to succeed.
- **Any other value** — the check waits that long, plus a small margin, and reports the elapsed
  time either way.

This is a default method, so adding it is a MINOR release. It costs an existing adapter nothing.

What it buys is that the failure becomes unarguable. A ledger that declares a thirty-second
window and has not released the key after thirty-five seconds has a defect by its own account of
itself, not by this suite's guess about what is reasonable. No knob, no tuning, and the client
states one fact they already know.

## The mutant

`M-16 BurnedKey`: the reference ledger, with the release removed. The claim is taken, the
transaction is refused, the outcome is reported correctly — and the claim stays. Every later
attempt on that key is refused with "a claim is already in progress", forever.

This is the defect the three-state rule exists to prevent, seen from the far side: the reference
ledger releases the key on `FAILED`, and the mutant is what it would be if it did not.

Expected to trip INV-16 alone. Every other invariant either uses a unique key per post, or
duplicates a transaction that succeeds, so no other check ever needs a key released.

## False-positive risk

- **A ledger with a lease longer than the budget fails.** Discussed above; the finding reports
  the elapsed time so the cause is visible rather than inferred.
- **A ledger that consumes a key permanently by design fails**, and should. "One attempt per
  key, ever" is a defensible API — but then a refused payment cannot be retried under the same
  key, the caller must mint a new one, and that is a contract the client's own retry logic has
  to know about. If it does not, the payment is lost. The finding is correct either way.
- **Gated behind `OVERDRAFT_GUARD`**, because the check needs the ledger to refuse a
  transaction for a business reason. Without a guard the first post applies, the key is
  legitimately consumed, and the check has nothing to observe — not-applicable rather than a
  failure.

## Impact on existing users

A new invariant in a MINOR release can turn a build red. This one fails a ledger that
permanently destroys a customer's ability to retry a refused payment, so a team meeting it for
the first time is learning something worth knowing. `--baseline` is the deliberate-adoption
path, and the changelog entry should say so in those words.

## Open questions for review

1. ~~Is five seconds the right budget?~~ ~~Should the budget be configurable?~~ **Resolved by
   review**: neither. The adapter declares its recovery window and the suite adapts. See above.
2. **Is the retry-with-the-same-key contract right?** This assumes a refused payment is
   retryable under its original key. That is what the three-state rule implies, but a ledger
   could reasonably require a fresh key after any failure, and the invariant would then be
   asserting a convention rather than a correctness property.

   If a fresh key turns out to be defensible, the invariant does not shrink — it changes shape.
   The property becomes *a refused transaction leaves the caller a way to make the payment*,
   which a ledger can satisfy either by releasing the key or by saying plainly that a new one
   is needed. What would then be the defect is answering "in progress" forever while offering
   neither. That is a better invariant than the one drafted here, and it is the version to
   write if the answer comes back that way.

   Still open.
