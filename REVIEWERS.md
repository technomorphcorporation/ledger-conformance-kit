# Reviewers

Two things live here: who reviews a new invariant before it ships, and who has changed this
kit by reading it.

## Reviewing an invariant

Every new invariant goes through an RFC in [`rfcs/`](rfcs/) before it is implemented — the
failure, why the existing invariants miss it, the check, the mutant, and the false-positive
risk. See [`CONTRIBUTING.md`](CONTRIBUTING.md).

A review is looking for one thing above all others: **would this fail a ledger that is
correct?** A false negative means a client ships with a clean scorecard and loses money anyway.
A false positive fails a correct ledger, their engineers find it, they are right, and the
credibility of every other finding goes with it. The second is worse, and it is the one an
author is least able to see in their own work.

## Acknowledgements

External review that changed the kit, with what it changed. Named with permission.

### Sameer Sood GS

Reviewed the published material in September 2026 and raised four points, three of which are
now in the documentation and one of which is becoming an invariant.

- **A claim can outlive the process that made it.** If a crash lands between writing
  `IN_FLIGHT` and settling it, every retry is told the operation is in progress, indefinitely.
  The suite had a check for concurrent duplicates and nothing for a stranded claim — it would
  have passed a ledger with this defect. An invariant is being written for it.
- **Idempotency keys need an eviction policy.** Recorded in `docs/01-core-topics.md`, together
  with the reason it is a correctness parameter rather than housekeeping: evict a committed key
  before the longest possible retry and the retry is not a duplicate any more, it is a second
  payment.
- **`ON CONFLICT` is not portable.** The pattern is, and the document now says how it maps to
  DynamoDB, Cassandra, MySQL and Redis — and names the constraint that genuinely does not
  travel, which is multi-partition atomicity for double-entry.
- **Zero rows updated is an outcome, not a reason.** Once the predicate carries more than the
  balance rule, "zero rows" stops meaning insufficient funds, and a customer is told the wrong
  thing about their own account. The document now separates reads that *decide* from reads that
  *explain*, which is the distinction that makes the fix obvious.
