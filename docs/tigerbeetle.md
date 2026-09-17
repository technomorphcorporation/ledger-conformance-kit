# The suite against TigerBeetle

This run exists to test the kit, not TigerBeetle.

TigerBeetle is built by people who treat correctness as the product: deterministic simulation
testing, strict serializability, a consensus protocol, and a public record of finding their own
bugs before anyone else does. The prior on a genuine defect here is low. The prior on a defect in
a young conformance suite driving a hand-written adapter is not.

So a clean run is the point. It is the strongest available evidence that the suite does not
manufacture findings — which is what licenses every finding reported against any other ledger.

## What was run

| | |
|---|---|
| Ledger | TigerBeetle **0.16.46**, single replica |
| Image | `ghcr.io/tigerbeetle/tigerbeetle:0.16.46` |
| Client | `com.tigerbeetle:tigerbeetle-java:0.16.46` — versions move in lockstep |
| Seeds | `42` and `8823714` |
| Adapter | `TigerBeetleLedgerAdapter`, in the test tree |

Reproduce with `./gradlew dockerTest --tests '*TigerBeetle*'`, optionally `-Plck.seed=…`. Skips
rather than fails without Docker.

**The adapter cannot ship, and that is not a compromise.** The client is a third-party jar
carrying a JNI native library; `lck` has zero runtime dependencies and `complianceCheck` fails the
build if that stops being true. There is no CLI flag for TigerBeetle, unlike Formance — this is a
calibration harness, not a feature.

## Result

**Fourteen invariants held. One was not applicable. Nothing failed, on either seed.**

Same verdicts on both seeds with different amounts. The adapter also passed all nine TCK checks
on the first attempt, which the Formance adapter did not.

| Invariant | Result |
|---|---|
| INV-01 balanced transactions | pass — **see the caveat** |
| INV-02 journal append-only | pass |
| INV-03 hash chain | *not applicable* |
| INV-04 idempotent replay | pass |
| INV-05 concurrent duplicate submission | pass (1 of 64 applied, 63 duplicate) |
| INV-06 concurrent credits | pass (500 of 500) |
| INV-07 conservation under transfer | pass (400 of 400) |
| INV-08 read path agrees with journal | pass |
| INV-09 overdraft guard | pass (10 of 20, 10 refused `ExceedsCredits`) |
| INV-10 one-subunit transfers | pass (1000 of 1000) |
| INV-11 cross-currency | pass — **and earned, unlike Formance** |
| INV-12 compensation | pass |
| INV-13 balances rebuildable | pass |
| INV-14 per-account sequencing | pass |
| INV-15 duplicate of a refused transaction | pass (32 of 32 refused, none reported applied) |

## The finding that outlived the run

The most useful thing this run produced is not a row in that table.

**TigerBeetle burns a failed transfer id.** Submit a transfer, have it refused for insufficient
funds, then fund the account so the identical transfer would now succeed, and resubmit under the
same id: TigerBeetle answers `IdAlreadyFailed` and posts nothing. The id is spent. A retry needs
a new one.

This is asserted directly, in `TigerBeetleAssumptionsTest.aFailedIdIsBurned`, because it is
load-bearing for something outside this file.

[RFC 0002](../rfcs/0002-a-claim-that-outlives-its-transaction.md) proposes INV-16, and its second
open question is whether a refused payment can be retried under its *original* key — or whether a
ledger may legitimately demand a fresh one. The RFC says that if a fresh key is defensible, the
invariant is asserting a convention rather than a correctness property and needs a different
shape.

**It is defensible, and this is the evidence.** The ledger with the strongest correctness claims
in this space requires a fresh id after a failure, deliberately and by design. So retry-under-the
-original-key is a convention. INV-16 has to be the other shape: the property is that *a refused
transaction leaves the caller a way to make the payment* — satisfiable either by releasing the key
or by saying plainly that a new one is needed — and the defect is answering "in progress" forever
while offering neither.

That question was holding the RFC open pending review. It is now answered by a run rather than by
an opinion, which is a better way to answer it.

## The caveat on INV-01

**INV-01 reports a pass TigerBeetle did not earn**, in the same way INV-11 does for Formance.

The invariant submits a deliberately unbalanced transaction — debit 24.00 against a credit of
23.00 — and expects refusal. A TigerBeetle transfer is balanced by construction: one amount,
debited from one account and credited to another. An unbalanced transaction has no representation,
so the adapter refuses it and the cluster is never asked.

The outcome is right — money cannot be created this way in TigerBeetle, structurally — but the
mechanism is not what the row implies. This is the same gap in the kit recorded in
[the Formance run](formance.md): an adapter has no way to report *this ledger's model cannot
express the transaction*, which is a third answer distinct from pass and fail, and the nearest
available one flatters the ledger.

Two ledgers have now hit it on different invariants, which moves it from a curiosity to a design
question worth solving.

**INV-11, by contrast, is earned here.** TigerBeetle can express a transfer between accounts in
different ledgers and refuses it itself with `AccountsMustHaveTheSameLedger`. Where Formance
forces the adapter to refuse, TigerBeetle does the refusing — so the same row means more here.

## Two bugs the run found, both of them ours

Both failed on the first run and both were mine. The second is the more interesting.

**1. Unpaired legs were silently dropped.** The unbalanced transaction in INV-01 was paired down
to the 23.00 that *did* match and the leftover 1.00 discarded, so a balanced transfer was sent and
applied. This is the identical bug the Formance adapter had, reintroduced — which says the leg
pairing wants to be one shared, tested thing rather than written twice.

**2. `IdAlreadyFailed` was mapped to `DUPLICATE`.** TigerBeetle carefully distinguishes `Exists`
— the transaction applied, stop retrying — from `IdAlreadyFailed` — it did not apply and never
will. Collapsing them told 31 of 32 concurrent callers their payment had already succeeded when
nothing had been written.

That is precisely the two-state idempotency defect INV-15 exists to catch, committed in the
adapter rather than in a ledger. **INV-15 caught it.** An invariant written against a defect
observed in production found the same defect in new code written by someone who knew the defect
well — which is a better argument for the suite than a clean table.

## Three more found while auditing the harness

The two above came from the run. These came from asking, afterwards, which parts of the harness
had never actually been exercised — and all three were checks that looked like protection.

**3. The version guard guarded nothing.** Client and server must be the same version, so
`assertVersionsMatch` compared the container tag against the client's
`getImplementationVersion()`. The tigerbeetle-java jar carries no `Implementation-Version` in its
manifest, so that value is always null and the check always passed. The version now comes from the
Gradle version catalog, handed to the test by the build, and an absent property fails rather than
passing quietly. Confirmed by making the two disagree and watching it fire.

**4. The pagination test never paged.** It posted 300 transfers against a query limit of 8189, so
the loop ran once and the test's name was the only thing claiming otherwise. The adapter now takes
a page limit as a test seam; the test uses 50 and posts 130, which spans three pages.

**5. The atomicity test had no teeth, and only a mutation check found that.** Every transaction
the kit's own invariants post has two legs, so the adapter's `LINKED` chain — which makes a
multi-transfer submission all-or-nothing — is never reached by the conformance run. Untested code
in an adapter whose only purpose is to be trustworthy is a liability, so it got a direct test.

The first version of that test passed with `LINKED` removed. It reused an account already drawn
down by the preceding case, so *both* legs failed on their own merits and the chain flag was never
what prevented the partial application. Fixed so exactly one leg is unfundable, and re-checked:
it now passes with the flag and fails without it.

## What this run does not show

- **Not a security review, benchmark, or audit.** Fifteen correctness properties, one workload.
- **One replica.** No consensus, no failover, no partition. TigerBeetle's most distinctive
  correctness machinery is in replicated operation, and none of it was exercised.
- **No fault injection.** Nothing kills a process or partitions a network; that is v2.0 work.
- **One invariant unmeasured.** `HASH_CHAIN` is not declared — see below.
- **One version.** 0.16.46.

## The mapping, and where it could be attacked

| Kit | TigerBeetle |
|---|---|
| `idempotencyKey` | the transfer `id`, a u128 supplied by the client |
| `DUPLICATE` | `Exists` |
| `REJECTED` | `ExceedsCredits`, `ExceedsDebits`, `IdAlreadyFailed`, a ledger mismatch |
| `APPLIED` | **no entry in the result batch** — success is reported by absence |
| `currency` | the `ledger` field, a non-zero u32 |
| `balance()` | `creditsPosted - debitsPosted` |
| `reset()` | a fresh id namespace, since there is no truncate |

Four decisions are worth arguing with, and all four are in the adapter's javadoc with reasoning.
The two a reviewer should go at first:

**The overdraft guard is an account property here and a transaction property in the kit.**
TigerBeetle sets `DEBITS_MUST_NOT_EXCEED_CREDITS` at account creation and never changes it; the
kit says `allowOverdraft` per transaction. They do not compose. The adapter decides the flag from
the first transaction that mentions an account — unguarded if it is the debit side of an
`allowOverdraft` transaction, guarded otherwise. That is only sound because `reset()` runs once per
invariant, so each invariant has its own namespace and a given account name has one consistent
intent within it. The residue: a guarded account can refuse a transfer the kit was willing to
overdraw, which lowers the applied count and cannot manufacture a finding, because every invariant
asserts on what the ledger applied rather than on what was submitted.

**`reset()` isolates rather than empties.** There is no truncate and no delete in TigerBeetle — a
data file is formatted once and only appended to. Every id the adapter derives is salted with a
per-run token plus a generation counter that `reset()` increments, and `journal()` filters on the
same salt. After a reset every account is one the cluster has never seen, so its balance is zero by
construction rather than by assertion, and earlier generations are unreachable rather than merely
ignored. Nothing is ever destroyed, which is the right property for a tool pointed at somebody
else's ledger. Asserted in `resetIsolatesWithoutDeleting`.

`HASH_CHAIN` stays undeclared. TigerBeetle's integrity machinery is real, but it is not a
per-entry chain this adapter can hand over, and declaring it would make INV-03 verify hashes the
adapter computed itself.

## Eleven behaviours pinned rather than assumed

`TigerBeetleAssumptionsTest` asserts every claim the adapter makes about TigerBeetle against a
live cluster, and `TigerBeetlePairingTest` covers the leg arithmetic without needing one, so a wrong claim fails in a test named after the claim instead of becoming a false
finding.

That mattered more here than for Formance. There is no HTTP to read with curl and no JSON to
inspect: the protocol is binary and the client is JNI. And the API's most consequential detail is
one a reasonable person assumes the other way round — **an empty result batch means everything
applied.** An adapter built on the opposite assumption would map success to a crash.
