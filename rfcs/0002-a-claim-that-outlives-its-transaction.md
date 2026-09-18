# RFC 0002: A claim that outlives its transaction

- **Status:** draft
- **Proposed invariant:** INV-16
- **Severity:** BLOCKER
- **Capability required:** OVERDRAFT_GUARD

Raised by [Sameer Sood](https://www.linkedin.com/in/sameersood/) in review of the published
material. See `REVIEWERS.md`.

**Reshaped after the TigerBeetle run.** The first draft asserted that a refused payment must be
retryable under its original key, and flagged as an open question whether a ledger might instead
demand a fresh one. It may: TigerBeetle burns a failed transfer id, so the identical transfer is
refused even after the account is funded and would now apply. That is asserted in
`TigerBeetleAssumptionsTest.aFailedIdIsBurned` and written up in `docs/tigerbeetle.md`. The draft
would therefore have failed a correct ledger, and its proposed mutant was a correct ledger. The
check below is the shape the question's answer requires.

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
strand the payment: refuse the transaction, report REJECTED correctly to everyone waiting at the
time, and then answer every later attempt on that key with "already applied". It passes fifteen
invariants while telling the one caller who comes back that money moved which never did.

Note what is *not* the gap, since the first draft had this wrong. A ledger that keeps the key and
says plainly that the attempt is spent has not stranded anyone — the caller mints a new key and
pays. Holding the key is a design choice; misreporting the outcome is a defect.

`ReferenceLedgerTest` covers this for the bundled reference implementation, which is how the
gap was noticed. That test is not part of the suite a client runs.

## The property

**A refused transaction must leave the caller a way to make the payment, and must never report
the refused payment as already applied.**

Two designs satisfy that, and the invariant accepts both:

- **The key is released.** The claim belonged to a transaction that never applied, so the key
  becomes usable again and a retry under it goes through. This is what the three-state rule
  implies and what a sweeper produces.
- **The key is spent, and the ledger says so.** A refusal is terminal: the caller is told plainly
  that this attempt did not apply and will not, so it mints a fresh key and proceeds. TigerBeetle
  works this way by design.

What neither of them does is claim the money has already moved.

## The check

Fund an account for one transfer. Post a transaction that must be refused — an amount the account
cannot cover, guard engaged — so the ledger claims the key and then fails. Fund the account
properly. Retry the identical key with an amount that will now succeed, up to the declared
recovery window, and read the outcome:

| Retry returns | Verdict | Why |
|---|---|---|
| `APPLIED` | **pass** | the key was released; the payment was made under it |
| `REJECTED` | **pass**, if a fresh key then applies | terminal answer; the caller can still pay |
| `DUPLICATE` | **fail** | nothing was written, and the caller has been told otherwise |

The third row is the whole invariant. `DUPLICATE` means *this already happened, stop retrying* —
and the balance proves it did not happen. A caller that believes it stops, and the payment is
lost silently, which is the incident this exists to prevent.

```
the key 'stranded' was claimed by a transaction that was refused, and 35.4s later a retry
is still answered DUPLICATE against a declared recovery window of 30s — balance 0.00, so
nothing was ever written, and the caller has been told the payment already succeeded
```

The message carries two numbers because a reader needs both: the elapsed time separates "released
slowly" from "never released", and the balance is what makes `DUPLICATE` a lie rather than an
opinion.

**The `REJECTED` row is not a free pass.** A ledger may refuse the retry, but it must not refuse
*the payment* — so the check then posts the same movement under a fresh key and requires it to
apply. A ledger that answers `REJECTED` to every key after a failure has stranded the caller just
as thoroughly as one that answers `DUPLICATE` forever, and fails on that second step.

## How long to wait: declared, not configured

The window is no longer the assertion, only the thing that separates a slow ledger from a broken
one. A correct ledger with a thirty-second lease answers `DUPLICATE` for thirty seconds and then
stops; the defect answers it forever. Without a bound there is no way to tell those apart, which
is why the window survives the reshaping even though the property it serves has changed.

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
window and is still answering `DUPLICATE` thirty-five seconds later has a defect by its own
account of itself, not by this suite's guess about what is reasonable. No knob, no tuning, and
the client states one fact they already know.

## The mutant

`M-16 StrandedClaim`: the reference ledger, with the release removed **and** the stranded claim
reported as `DUPLICATE`. The claim is taken, the transaction is refused, and every later attempt
on that key is answered "already applied" — forever, while the balance shows nothing was written.

The first draft named this mutant `BurnedKey` — a ledger that keeps the claim and refuses later
attempts. **That ledger is correct**, and shipping it as the mutant would have made M-16 assert a
convention: TigerBeetle behaves that way deliberately. The defect is not keeping the key; it is
lying about what happened to the money.

Expected to trip INV-16 alone. Every other invariant either uses a unique key per post or
duplicates a transaction that succeeded, so no other check ever observes a duplicate of a
*refused* transaction — except INV-15, which is the same lie told to concurrent callers in the
moment rather than to one caller afterwards. If M-16 trips both, that is a correct blast radius
and `MutationTest` should record it as such rather than have it narrowed.

## False-positive risk

- **A ledger with a lease longer than the budget fails.** Discussed above; the finding reports
  the elapsed time so the cause is visible rather than inferred.
- **A ledger that consumes a key permanently by design now passes**, which is the reshaping. The
  first draft failed it and argued the finding was correct anyway, on the grounds that a client's
  retry logic might not know about the contract. That argument was wrong: it is a finding about
  the client's retry logic, not about the ledger, and this kit does not test the caller. What the
  ledger owes is a truthful terminal answer and a way to pay — not a particular key lifecycle.
- **Gated behind `OVERDRAFT_GUARD`**, because the check needs the ledger to refuse a
  transaction for a business reason. Without a guard the first post applies, the key is
  legitimately consumed, and the check has nothing to observe — not-applicable rather than a
  failure.

## Impact on existing users

A new invariant in a MINOR release can turn a build red. This one fails a ledger that tells a
caller a refused payment already succeeded, so a team meeting it for the first time is learning
something worth knowing. `--baseline` is the deliberate-adoption path, and the changelog entry
should say so in those words.

Worth stating what the reshaping cost: the draft would also have failed every ledger that spends
a key on a failed attempt, which is a legitimate design and a larger population than the one with
the defect. That is the difference between an invariant and a house style, and it is why this RFC
is not being merged on the strength of the argument that produced it.

## Open questions for review

1. ~~Is five seconds the right budget?~~ ~~Should the budget be configurable?~~ **Resolved by
   review**: neither. The adapter declares its recovery window and the suite adapts. See above.
2. ~~Is the retry-with-the-same-key contract right?~~ **Resolved by evidence.** A fresh key is
   defensible: TigerBeetle burns a failed transfer id and refuses the identical transfer even once
   it would apply. So retry-under-the-original-key is a convention, not a correctness property,
   and the draft's version of this invariant would have failed a correct ledger. The check is now
   the shape this question predicted — *leaves the caller a way to make the payment* — and it is
   the run that decided it rather than an argument.

3. **Does `DUPLICATE` carry enough information?** The kit has three post outcomes and no way to
   say "a claim is in flight". An adapter over a ledger that distinguishes *in progress* from
   *already applied* has to collapse them, and this check then cannot tell a ledger mid-lease from
   one that is lying — it can only wait out the window and see which it was. A fourth outcome
   would make the distinction observable, and would also give INV-15 a sharper failure message.
   That is an `lck-spi` change and therefore a MAJOR release, so it is raised here rather than
   assumed. **Open.**

4. **Should the second step be part of this invariant or its own?** Requiring a fresh key to apply
   after a `REJECTED` retry is a different property — *the ledger has not stranded the payment* —
   and bundling it means one failure message has to explain two things. Kept together for now
   because neither half is meaningful alone. **Open.**
