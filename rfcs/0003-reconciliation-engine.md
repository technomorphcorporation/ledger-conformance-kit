# RFC 0003 — Reconciliation: comparing two sources of truth

| | |
|---|---|
| **Status** | Draft |
| **Author** | Manjul Bhakri |
| **Created** | 2026-10-08 |
| **Affects** | `lck-spi` (additive), `lck` (new check family), reports |
| **Depends on** | — |
| **Related** | RFC 0004 (differential conformance), RFC 0005 (evidence pack), RFC 0006 (one-to-many matching), `docs/01-core-topics.md` §reconciliation |
| **Revision** | r2 — adds §7 Scaling and data variety, fixtures C-06, D-10, D-11 |

---

## Summary

Add a second, read-only adapter type — `RecordSource` — and a reconciliation engine that compares
two of them: a ledger and a bank statement, the books and a processor, a legacy store and its
replacement. The engine reports **completeness**, **accuracy** and **tie-outs**, classifies every
break into a closed taxonomy, and states the money impact of each break in minor units.

Reconciliation checks are a new family, `REC-xx`, distinct from the fifteen `INV-xx` invariants.
An invariant is a property one system must hold. A reconciliation check is a property two systems
must hold *relative to each other*.

## Motivation

The fifteen invariants answer *"is this ledger internally correct?"* They cannot answer the
question most teams are actually asked: *"does your record match theirs?"* — the sponsor bank's
pooled balance, the processor's settlement file, the custodian's position file, the system you are
migrating away from.

Today that question is answered with spreadsheets, sampled spot-checks and row counts. Row counts
pass when one row is missing and another is duplicated. Sampling passes when the error is in the
rows nobody sampled. Neither says *why* the records disagree, so the same break recurs.

The kit already has the discipline this needs — explicit capabilities, an adapter TCK, measurement
against what was *applied* rather than submitted, no false positives on a correct system. This RFC
extends that discipline to two systems.

## Non-goals

- **Not a reconciliation product.** No scheduling, no UI, no workflow, no break-management queue.
  The output is a finding, as everywhere else in the kit.
- **No inference.** The engine never guesses how fields map between sources. Mappings are
  declared, and an undeclared field is reported, never silently compared.
- **No tolerance by default** on money. A tolerance must be declared, and every declared tolerance
  is printed in the report next to the breaks it suppressed.
- **No production access** beyond read-only scans of a scope the operator chooses.
- **No new runtime dependencies** in `lck-spi` or `lck`.

---

## Design

### 1. The `RecordSource` SPI

Read-only, additive to `lck-spi`, Java 17, zero dependencies. It does not change `LedgerAdapter`.

```java
public interface RecordSource {
    String name();
    Set<RecordCapability> capabilities();      // SORTED_SCAN, AGGREGATES, AS_OF
    RecordSchema schema(String dataset);       // keys, fields, money and time semantics
    Iterator<Record> scan(String dataset, Scope scope);
    Optional<Aggregates> aggregates(String dataset, Scope scope, List<String> groupBy);
}
```

- **`Scope`** — a business-date range, plus optional partition and key-range filters. Every result
  is reported against the scope it was taken over.
- **`SORTED_SCAN`** — the source returns records in business-key order. v1 requires it on both
  sides; the engine streams a merge and holds nothing in memory beyond the current pair.
- **`AGGREGATES`** — the source can compute counts and sums server-side, so tie-outs do not need a
  full scan.
- **`AS_OF`** — the source can answer for a past point in time. Without it, scope means "now", and
  the report says so.

A source that declares nothing still works: the engine falls back to a full sorted scan, and checks
that need an undeclared capability report **not applicable**.

### 2. The mapping — declared, never inferred

A reconciliation run is configured by a `Mapping` between source A and source B:

| Element | Declares |
|---|---|
| **Key** | The business key on each side, field by field |
| **Money fields** | Field pairs, currency field, comparison scale, rounding mode |
| **Time fields** | Field pairs, the zone each side records in, the zone to compare in |
| **Equivalences** | Null vs empty string, whitespace trimming, case — each opt-in |
| **Code maps** | Explicit value translations (e.g., `"D"` ↔ `"DEBIT"`) |
| **Ignored fields** | Fields deliberately not compared — listed in the report |
| **Tolerances** | Per money field, with a required free-text justification |

Every field on either side must be mapped, code-mapped or explicitly ignored. An unaccounted field
fails the mapping check before any comparison runs, for the same reason the adapter TCK exists: a
silent gap produces a clean-looking report that means nothing.

### 3. The comparison, in three passes

**Pass 1 — Tie-out (cheap).** Counts and money sums per partition, per currency, on both sides.
Uses `AGGREGATES` where available. A partition that ties out is recorded as tied; one that does not
is queued for pass 2. *A tie-out alone never reports "matched"* — offsetting errors tie out — so
pass 2 runs on every partition unless the operator chooses tie-out-only, which the report states.

**Pass 2 — Completeness.** A streaming merge on the business key:
- key on A only → `MISSING`
- key on B only → `EXTRA`
- key repeated on one side → `DUPLICATE_KEY`

**Pass 3 — Accuracy.** For every key on both sides, each mapped field is normalised (§2) and
compared. Records may be compared by a canonical hash first — SHA-256 over a canonical encoding of
the normalised fields, using `java.security.MessageDigest` — and only mismatching hashes are
compared field by field.

### 4. The break taxonomy — closed, and every break gets exactly one class

| Class | Meaning | Default severity |
|---|---|---|
| `MISSING` | Present on A, absent on B | BLOCKER |
| `EXTRA` | Present on B, absent on A | BLOCKER |
| `DUPLICATE_KEY` | Key appears more than once on one side | BLOCKER |
| `AMOUNT` | Money fields differ beyond declared scale and tolerance | BLOCKER |
| `PRECISION` | Money fields differ only below the comparison scale | MINOR |
| `TIMING` | Same record, time field on opposite sides of a scope boundary after zone normalisation | MAJOR |
| `MAPPING` | Values differ by a consistent transformation across many records (sign, code, unit) | MAJOR |
| `FIELD` | A non-money mapped field differs | MAJOR |
| `SCHEMA` | A mapped field is absent or of an incompatible type on one side | BLOCKER |

`MAPPING` is the only class inferred from a pattern rather than from a single pair, and it is
reported as a *suspected* class with the evidence — "312 of 312 `AMOUNT` breaks in `fee` differ by
exactly ×−1" — never applied silently.

### 5. What a finding says

As with invariants: a finding names something you can look up again.

```
REC-03   FAIL   BLOCKER  Every key on A is present on B
         dataset=postings  scope=2026-09-01..2026-09-30
         MISSING  14 records   impact USD -2,184.50   EUR -310.00
         first:   key=TX-000184213  posted 2026-09-04
```

Money impact is always stated **per currency, in minor units, never summed across currencies**.

### 6. The checks

| Ref | Check | Severity |
|---|---|---|
| REC-01 | The mapping accounts for every field on both sides | BLOCKER |
| REC-02 | Every partition ties out on count and on money per currency | BLOCKER |
| REC-03 | Every key on A is present on B | BLOCKER |
| REC-04 | Every key on B is present on A | BLOCKER |
| REC-05 | No business key is duplicated on either side | BLOCKER |
| REC-06 | Every money field agrees at the declared scale | BLOCKER |
| REC-07 | Every non-money mapped field agrees | MAJOR |
| REC-08 | No record crosses a scope boundary under zone normalisation | MAJOR |


### 7. Scaling and data variety

§3 describes a sorted streaming merge executed inside the kit. It is exact and simple, and it is the
right design up to roughly tens of millions of records. Beyond that it fails for a structural
reason: it moves every record to the comparison. At large scale the cost is transfer and decoding,
not comparison. The principle has to flip — **move the comparison to the data, and pull back only
what disagrees.**

#### 7.1 Execution tiers

The engine chooses a tier from the capabilities each source declares. The report always states
which tier ran.

| Tier | Typical volume | How it runs | Needs |
|---|---|---|---|
| **T1 — streaming merge** | up to ~10⁷ records | §3 as written | `SORTED_SCAN` |
| **T2 — fingerprint and narrow** | ~10⁷ to ~10¹⁰ | Tie-out and fingerprints computed in each engine; only mismatching ranges are pulled | `AGGREGATES`, `FINGERPRINT` |
| **T3 — incremental** | unbounded, continuous | T2 applied to new partitions only; matched partitions keep their fingerprints | T2 plus `AS_OF` or stable partitioning |

The volumes are indicative. What decides the tier is capability, not row count.

#### 7.2 New capabilities

- **`FINGERPRINT`** — the source computes, inside its own engine, a single fingerprint for a scope
  and key range: each record's mapped fields canonically encoded, hashed, and combined
  order-independently. Signature, additive to `RecordSource`:

  ```java
  Optional<Fingerprint> fingerprint(String dataset, Scope scope, KeyRange range, CanonicalSpec spec);
  ```

- **`BUCKETED_SCAN`** — the source can read records grouped by `hash(key) mod N`, without a global
  sort. Many columnar and distributed stores cannot sort cheaply; bucketing removes the need.

#### 7.3 Fingerprint and narrow

1. **Tie-out in-engine** — counts and money sums per partition and currency, as queries the engine
   executes. Columnar engines read only the referenced columns and prune partitions outside scope.
2. **Fingerprint each partition** on both sides. Equal fingerprints → the partition is recorded as
   matched *by fingerprint*, and the report says so.
3. **Narrow mismatches** — split the range (by key range, or by bucket) and fingerprint each half.
   Recurse until a range falls below a configured size, then fetch those records and run §3's
   completeness and accuracy passes on them.

Data transferred is proportional to the amount of difference, not to the size of the dataset.

#### 7.4 Hash parity — the hard part

Fingerprints only compare if both engines produce **byte-identical canonical encodings** for equal
values, and apply the **same hash function**. Every difference in decimal formatting, timestamp
format, null representation, string encoding or collation produces a false mismatch on every row.

So the canonical encoding is generated from the declared `Mapping` (§2), not hand-written per
source: each source adapter translates the `CanonicalSpec` into its own engine's expressions —
explicit decimal scale and format, timestamps rendered in the declared comparison zone in a fixed
format, a fixed null token, a fixed field separator, UTF-8. The hash must be one both engines
provide natively, declared in the spec. Combining is order-independent so that row order never
matters.

Hash parity is not assumed. It is **proven per adapter pair** by fixture C-06 before a T2 run is
allowed: if the same canonical rows do not fingerprint identically on both engines, T2 is refused
and the run falls back to T1 on a reduced scope, with the reason reported.

A matched fingerprint carries a stated collision bound in the report. It is evidence of equality,
not proof, and the report never says otherwise.

#### 7.5 Distributed engines

Where the comparison itself must run distributed — very large scopes on a cluster — that execution
lives in **separate, optional modules**, never in `lck-spi` or `lck`. The core stays
dependency-free; the module depends on the core, not the reverse.

#### 7.6 Data variety

| Shape | Supported | Requirement |
|---|---|---|
| Structured tables | Yes | As designed |
| Semi-structured (JSON, nested) | Yes | Declared paths; canonical key order and number format before hashing |
| Files (CSV, fixed-width statements) | Yes | A file `RecordSource` with explicit parsing, encoding and delimiter rules |
| Event streams | Partly | Reconcile point-in-time state snapshots, or events by id within a declared window |
| Mixed granularity (net totals vs. detail) | Tie-out only | The report states that detail comparison was not possible |
| One-to-many matching (batches, netting, fees) | **Not in this RFC** | See RFC 0006 |

#### 7.7 Where it does not work — stated, not hidden

- **No stable business key.** The engine refuses rather than guesses.
- **Sources changing mid-run.** Without a consistent snapshot or `AS_OF`, differences may be timing;
  the report flags the run as unquiesced.
- **Tokenised or masked fields.** Comparable only if both sides tokenise identically; otherwise
  ignored and listed.
- **Personal data.** T2 compares fingerprints, so most of a run never extracts raw values — an
  advantage, stated in the evidence pack.
- **Cost and load.** On pay-per-compute engines every run costs money. Runs should be scoped by
  date, executed against replicas or snapshots where possible, and the evidence pack records the
  tier and the number of narrowing rounds so cost is visible.

#### 7.8 Domain

The engine is domain-neutral: it compares records. The domain lives in the `Mapping` — what is
money, what identifies a record, which tolerances are justified. The kit's scope remains the money
path; general-purpose data-quality testing is out of scope by design.

---

## Verification — the discrepancy corpus

The invariants are protected by a mutant corpus. Reconciliation needs the same, in two halves.

**No false positives.** Each of these pairs must reconcile clean:
- **C-01** identical sources
- **C-02** same values, different decimal scale on each side (`12.5` vs `12.5000`)
- **C-03** same instants, recorded in different zones
- **C-04** null on one side, empty string on the other, with the equivalence declared
- **C-05** same records, different physical order on an unsorted source with sorting applied
- **C-06** the same canonical rows loaded into each engine of an adapter pair must fingerprint identically — the gate for any T2 run

A check that reports a break on any of these is a bug in the check.

**No false negatives.** Each of these must trip *exactly* its named checks, and no others:

| Fixture | Injected discrepancy | Must trip |
|---|---|---|
| D-01 | One record deleted from B | REC-03 |
| D-02 | One record added to B | REC-04 |
| D-03 | One record duplicated on B | REC-05 |
| D-04 | One amount off by one minor unit | REC-06 |
| D-05 | Amounts truncated instead of rounded on B | REC-06, classified `PRECISION` only below scale |
| D-06 | Timestamps shifted by a zone offset near midnight | REC-08 |
| D-07 | Sign flipped on every fee | REC-06, classified suspected `MAPPING` |
| D-08 | One record deleted and one duplicated in the same partition | REC-03, REC-05 — **and REC-02 still passes**, proving tie-out alone is insufficient |
| D-09 | A field present on A with no mapping | REC-01 |
| D-10 | One field changed in one record of a 10⁷-record partition | REC-06 — found by T2 narrowing, with the narrowing rounds recorded |
| D-11 | Keys skewed so one hash bucket holds most records | REC-03 / REC-04 as injected — bucketing must not lose or double-count records |

D-08 is the reason this RFC exists, and must never be removed from the corpus.

---

## Compatibility

- `LedgerAdapter` and the fifteen invariants are unchanged.
- `RecordSource` is additive to `lck-spi`; `lck-spi` stays dependency-free.
- A `LedgerAdapter` can be wrapped as a `RecordSource` over `journal()`, which RFC 0004 relies on.
- Reports follow the existing rule: no remote assets, enforced by `complianceCheck`.

## Open questions

1. **Narrowing threshold.** What range size should end T2 narrowing and trigger a record fetch — fixed, or derived from record width and transfer cost?
2. **Sampling.** Should the engine ever offer statistical sampling for very large scopes? If so, the
   report must state the sample size and the confidence explicitly — and sampling must never be
   able to produce a "matched" result for a partition.
3. **`MAPPING` thresholds.** How many consistent breaks before a pattern is reported as a suspected
   mapping — a fixed count, a proportion, or both?
4. **HTTP route.** Should `RecordSource` get the same language-agnostic HTTP contract as
   `LedgerAdapter`?
5. **Hash choice.** Should the spec mandate one hash function for T2, or let each adapter pair declare one that both engines provide natively?
