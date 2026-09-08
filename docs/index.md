# Ledger Conformance Kit

Fourteen executable invariants that any system moving money is expected to hold. You implement
a five-method adapter; the suite reports, in currency subunits, which properties your ledger
breaks under concurrency, retry and replay.

A failing invariant is not a code-review opinion. It is a reproducible run against your own
adapter, and it prints the exact discrepancy.

```
INV-06   FAIL   BLOCKER  No lost updates on a hot account
                         balance 187.00 after 500 of 500 credits applied,
                         expected 500.00 — lost 313.00
```

## Using it

```kotlin
testImplementation("io.github.technomorphcorporation:lck-junit5:1.0.0")
```

`lck-spi` — the artifact you compile your adapter against — has **zero dependencies** and
targets Java 17. `lck-junit5` is the only module that pulls a third-party jar.

## Start here

- [**Writing an adapter**](adapter-guide.md) — three routes in, and the five mistakes that
  produce findings about your adapter rather than your ledger
- [**The HTTP contract**](http-adapter.md) — four test-only endpoints, so a ledger in any
  language becomes testable without writing Java

## Background

Notes behind the invariants. Useful whether or not you run the kit.

- [**The six things that decide whether a money system is correct**](01-core-topics.md) —
  hot-account concurrency, idempotency, event sourcing, sagas and compensation, money
  arithmetic, reconciliation
- [**Three reference architectures**](02-architectures.md) — a high-concurrency wallet ledger,
  multi-custodian reconciliation, and regulatory reporting off the same journal, with schema
  and the annotations about what breaks

## Project

- [**Releasing**](releasing.md) — how a version is cut, and what is checked before it is
- [**Publication protocol**](publication-protocol.md) — what is written about work done with
  the kit, and what is not
- [Source, issues and the full README](https://github.com/technomorphcorporation/ledger-conformance-kit)

## What it does not do

It reports which of fourteen properties held, under one workload, on one run. It does not
score, grade, or certify, and passing it does not mean a ledger is correct — a suite can only
report on the properties someone thought to encode. `Mutants.java` in the repository is the
evidence that each one catches what it claims to: every invariant ships with an injected defect
that trips it, and the build fails if a mutant trips anything it does not declare.
