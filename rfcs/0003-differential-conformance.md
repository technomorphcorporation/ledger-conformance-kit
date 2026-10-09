# RFC 0003 — Differential conformance: does the new system behave like the old one?

| | |
|---|---|
| **Status** | Draft |
| **Author** | Manjul Bhakri |
| **Created** | 2026-10-08 |
| **Affects** | `lck` (new run mode), `lck-junit5` (new annotation), reports |
| **Depends on** | — |
| **Related** | RFC 0004 (evidence pack) |
| **Revision** | r2 — state comparison is native; the reconciliation dependency is gone |

---

## Summary

Run the same seeded workload, and the same invariants, against two `LedgerAdapter`s — a
**baseline** and a **candidate** — and report every place they disagree: an invariant that held on
the baseline and breaks on the candidate, a capability the candidate no longer declares, or a final
state that differs.

Reconciling migrated data proves the history arrived. Differential conformance proves the new
system *behaves* the way the old one did.

**This RFC needs no new SPI and no new adapter type.** An earlier draft routed the state
comparison through a general reconciliation engine, which was the wrong shape: that engine
exists to compare two unrelated sources with declared field mappings, over datasets too large
to hold in memory. Here both sides implement the same interface, the workload is bounded and
seeded, and the fields are the kit's own model. The comparison is a few hundred lines against
`balance()` and `journal()`, and making it native is what lets this ship on its own.

## Motivation

When a team replaces a ledger — a new vendor, a new database, a rewrite, a move between providers —
the question they're asked is *"is the new one correct?"* The usual answer is a data
reconciliation of the migrated records, which proves the past was copied. It says nothing about the
future: whether the new system still collapses duplicate submissions, still guards an overdraft
under a concurrent drain, still rebuilds from its event log.

Those properties are exactly the ones a migration loses quietly. The old system's idempotency was
often an accident of its storage engine; the new one's is whatever someone remembered to rebuild.
The fifteen invariants already describe those properties. This RFC runs them twice and compares.

## Non-goals

- **Not a performance comparison.** Latency and throughput are out of scope, as everywhere in the
  kit.
- **No production.** Both adapters point at scratch environments. The suite writes.
- **No equivalence claims beyond what was measured.** A clean differential run means the two
  systems agreed on these properties under this workload — not that they are equivalent.

---

## Design

### 1. Two adapters, one workload

```java
@DifferentialConformance(
    baseline  = LegacyLedgerAdapter.class,
    candidate = NewLedgerAdapter.class,
    seed = 42)
class MigrationConformanceTest extends DifferentialTests { }
```

Both adapters must pass the adapter TCK first. If either fails, the differential run is suppressed
— the existing rule, applied to both sides.

### 2. Two modes, because concurrency is not deterministic

The kit already states that the seed fixes the workload but not the interleaving of a race. A
differential comparison has to respect that, so it runs in two modes:

**Serial mode.** The seeded workload is applied one transaction at a time. Outcomes are
deterministic, so the final states must match **exactly**: every balance per account and currency,
and the journal projection.

The comparison is direct. For every account and currency either side touched, read `balance()`
from both and compare; then compare the two `journal()` projections entry by entry. Three
differences are possible and each is reported with its money impact per currency, in minor
units, never summed across currencies:

| Difference | Reported as |
|---|---|
| A balance differs | `BALANCE`, with both figures and the delta |
| An entry the baseline has and the candidate does not | `MISSING`, with the amount |
| An entry the candidate has and the baseline does not | `EXTRA`, with the amount |

A union of accounts is used rather than the baseline's set alone, so a candidate that invents
an account is a difference rather than something nobody looked for.

**Concurrent mode.** The invariants run as they do today, on both systems. Exact amounts are
expected to differ between runs, so the comparison is on **outcome class** per invariant — held,
broke, not applicable — never on cent figures. This is the same stability claim the kit already
makes: what is stable is *which* invariants break.

### 3. What is compared

| Comparison | Baseline | Candidate | Reported as |
|---|---|---|---|
| Invariant outcome | held | broke | **Regression** — BLOCKER |
| Invariant outcome | broke | held | Improvement — informational |
| Invariant outcome | held | not applicable | **Capability loss** — MAJOR |
| Capability declared | yes | no | **Capability loss** — MAJOR |
| Serial final state | — | — | Every difference, classified per RFC 0003 |
| Rejection behaviour | rejected | applied | **Regression** — BLOCKER |
| Rejection behaviour | applied | rejected | **Divergence** — MAJOR |

The rejection comparison matters: a candidate that applies a transaction the baseline refused has
changed a business outcome, even if every invariant still holds.

### 4. The checks

| Ref | Check | Severity |
|---|---|---|
| DIF-01 | No invariant that held on the baseline breaks on the candidate | BLOCKER |
| DIF-02 | The candidate declares every capability the baseline declares | MAJOR |
| DIF-03 | Under the serial workload, final balances agree per account and currency, over the union of accounts either side touched | BLOCKER |
| DIF-04 | Under the serial workload, the journal projections agree entry by entry | BLOCKER |
| DIF-05 | Both systems accept and reject the same transactions | BLOCKER |

### 5. What a finding says

```
DIF-01   FAIL   BLOCKER  No invariant regresses on the candidate
         INV-05  baseline=held   candidate=broke
         candidate: 3 of 64 submissions of one key applied, expected at most 1
         seed=42  — re-run concurrent mode to confirm; the race outcome is not seed-stable
```

The last line is not optional. A concurrency regression observed once is reported with that caveat,
consistent with how single-system findings are reported today.

---

## Verification

Differential conformance reuses the existing mutant corpus, which already declares, for each
mutant, the exact invariants it should break.

**No false positives.**
- **P-01** baseline = reference, candidate = reference → zero differences in both modes.
- **P-02** baseline = reference, candidate = reference with a different, equivalent storage
  encoding → zero differences. This proves the comparison is on behaviour, not representation.

**No false negatives.** For every mutant `M-nn` in the corpus:
- baseline = reference, candidate = `M-nn` → `DIF-01` must report a regression on **exactly** the
  invariants `M-nn` declares, and no others.

That ties the differential run's correctness to a corpus that already exists and is already
enforced on every push — so it cannot drift from the invariants it compares.

**Serial mode** additionally needs three fixtures of its own:
- **S-01** candidate rounds a single posting differently → `DIF-03` reports exactly one
  `BALANCE` difference, with its impact in minor units.
- **S-02** candidate drops one journal entry while keeping the balance correct → `DIF-04`
  reports exactly one `MISSING`, and `DIF-03` passes. A candidate can be right about the
  balance and wrong about the history, and the two checks must separate those.
- **S-03** candidate writes to an account the baseline never touched → `DIF-03` reports it.
  This is the fixture that fails if the comparison iterates the baseline's accounts instead of
  the union, which is the mistake a first implementation makes.

---

## Compatibility

- No change to `LedgerAdapter`, the invariants, or single-system runs.
- A new annotation and a new `lck` run mode; existing builds are unaffected.
- **No new SPI, no new dependency.** `lck-spi` and `lck` keep their zero-dependency guarantee,
  and `complianceCheck` continues to enforce it.

## Open questions

1. **Run count in concurrent mode.** Should a regression require *N* consecutive concurrent runs
   before it is reported as stable, with a single occurrence reported as "observed once"?
2. **Partial migrations.** Should a differential run be able to compare a subset of accounts or
   currencies, for systems migrated in stages?
3. **Historical replay — probably its own RFC, not an option on this one.** Driving the candidate
   from a replay of the baseline's own journal would cover the transactions a real system actually
   saw, which is more convincing than any seeded workload. It also means reading real transaction
   history and writing it somewhere, which is a different risk class from everything else the kit
   does: scratch copies only, provenance stated in the report, and TCK-00's refusal of a non-empty
   environment doing real work. Raised here so it is on the record, and deliberately not designed
   here.
4. **Vacuity.** If both adapters wrap the same underlying store — a plausible mistake during a
   migration rehearsal — every check passes and the run means nothing. The kit has the same
   problem on INV-08 and INV-13 against a ledger whose balance *is* its journal projection. Worth
   solving once, for both.
