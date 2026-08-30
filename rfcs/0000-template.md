# RFC 0000: <title>

- **Status:** draft | accepted | rejected
- **Proposed invariant:** INV-nn
- **Severity:** BLOCKER | MAJOR | MINOR
- **Capability required:** none | HASH_CHAIN | OVERDRAFT_GUARD | COMPENSATION | REPLAY

## The failure

The incident an engineer would recognize. Concrete: the timeout, the retry, the two
concurrent requests. Not an abstract property.

## Why existing invariants do not catch it

If an existing invariant catches it, extend that one instead of adding a new one.

## The check

How the invariant exercises it, and what number it reports on failure.

## The mutant

The single defect injected into `ReferenceLedger` that this invariant catches and that no
other invariant catches alone. Without this, the RFC is incomplete.

## False-positive risk

Which correct-but-unusual ledger designs might fail this check, and whether that means it
needs a `Capability` gate.

## Impact on existing users

A new invariant can turn a client's build red on a MINOR upgrade. Note it for the changelog.
