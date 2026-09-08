# RFC 0001: A duplicate of a transaction that never applied

- **Status:** accepted
- **Proposed invariant:** INV-15
- **Severity:** BLOCKER
- **Capability required:** OVERDRAFT_GUARD

## The failure

A customer's transfer is refused for insufficient funds. Their client library, which timed
out waiting, retries with the same idempotency key — and is told the payment has already
been applied.

Nothing was written. The retry stops, because a duplicate response means success. The money
never moves, and no error is recorded anywhere: the ledger refused a payment, the caller
believes it succeeded, and the two never reconcile because neither side thinks anything went
wrong.

The mechanism is an idempotency record with two states instead of three. The key is claimed
before the outcome is known, a concurrent submission sees the claim and answers DUPLICATE,
and the claim is withdrawn afterwards when the transaction turns out to be refused. The
window is small and the failure is silent, which is why it survives review.

## Why existing invariants do not catch it

INV-04 and INV-05 both fund the account before submitting, so the transaction under test
always succeeds. They establish that a duplicate does not apply twice; neither can observe
what a duplicate is told when the original *fails*, because in both the original never does.

A ledger that collapses IN_FLIGHT and COMMITTED passes all fourteen existing invariants.
This was demonstrated on the reference implementation itself, which carried exactly this
defect until it was found by reading rather than by a failing test.

## The check

Seed an account for one transfer, then release N simultaneous submissions of one idempotency
key for an amount the account cannot fund, with the overdraft guard engaged. Every submission
must be refused.

The finding reports how many callers were told their payment had already been applied, and
the amount they believe moved:

```
7 of 32 simultaneous submissions were told DUPLICATE for a transaction that never
applied — those callers believe 50.00 was transferred, and nothing was written
```

It also asserts the balance did not move, so a ledger that answers correctly and posts
anyway is still caught.

## The mutant

`M-15 WithdrawnClaim`: the reference ledger with its claim-and-settle protocol replaced by a
marker. The key is claimed with `putIfAbsent`, a concurrent submission that sees the claim is
told DUPLICATE immediately, and the claim is removed if the transaction is refused.

This is the defect the reference ledger actually had. It trips INV-15 and nothing else: every
other invariant submits either unique keys, or duplicates of a transaction that succeeds.

## False-positive risk

Gated behind `OVERDRAFT_GUARD`. Without a guard the transaction would be applied rather than
refused and the check would be meaningless, so a ledger with no sufficient-funds concept
reports not applicable rather than failing.

A ledger that makes a concurrent duplicate **wait** for the outcome and then answers REJECTED
passes, as does one that answers "retry" — those are the two correct behaviours. A ledger that
answers DUPLICATE only after the original has committed also passes, because then the
duplicate is true.

The check does not require any particular number of submissions to be refused for a specific
reason, only that none is described as already applied. That leaves a ledger free to reject
for its own reasons — rate limiting, write conflict — without failing.

## Impact on existing users

A new invariant in a MINOR release can turn a client's build red. This one fails a ledger with
a genuine defect that silently loses payments, so a team meeting it for the first time is
learning something true. `--baseline` exists for adopting it deliberately.
