# CLAUDE.md

Context for Claude Code working in this repository.

## What this project is

The Ledger Conformance Kit: fifteen executable invariants that any system moving money
is expected to hold. A client implements a five-method adapter; the suite reports, in
currency subunits, which properties their ledger breaks under concurrency, retry and
replay.

The tool is only worth something if an engineer at a bank or a fintech trusts it on first
read. A single false finding, an unexplained dependency, or a claim it cannot back up
costs more than any feature adds. That constraint drives most of the rules below.

## Non-negotiables

Violating any of these is worse than not shipping the change.

1. **`lck-spi` has zero dependencies and targets Java 17.** Clients compile their adapter
   against it. Adding a dependency fails `complianceCheck`. Changing anything public is a
   MAJOR release.
2. **`lck` also has zero runtime dependencies.** Only `lck-junit5` pulls a third-party jar.**
   Enforced by `./gradlew complianceCheck`.
3. **No telemetry, no phone-home, no update check, no license call.** The only outbound
   connection permitted is to the adapter endpoint the operator configures. Grep-enforced.
4. **Money is `long` subunits.** Never `double`, never `float`, never `BigDecimal` on a
   hot path. A `double` in a money position is a bug even if a test passes.
5. **Every failure message contains a number.** `"lost 498.00 of 500.00"`, not
   `"INV-06 failed"`. A number is a defect report; a boolean is an opinion.
6. **Assert against what the ledger applied, never against what was submitted.** A refused
   write posts nothing and loses nothing. Count outcomes with `Invariants.Tally` and report
   rejections as observation. An invariant that requires every submission to succeed is
   asserting throughput, not correctness, and will fail a conservative ledger that is right.
7. **Every invariant must be reproducible from its seed.** All non-determinism goes
   through `Invariants.Harness`. Never call `Math.random()` or `System.nanoTime()` for
   test data.
8. **A new invariant ships with a mutant.** See below.

## Repository map

Three Gradle modules. Each exists because it has a different **dependency profile** —
that is the only good reason to split a build.

```
lck-spi/     zero deps, Java 17 bytecode.  Model, LedgerAdapter, Capability.
             What CLIENTS compile against. Small enough to review, old enough to consume.
lck/         zero deps, Java 21.  The engine:
               core/      Invariants (the registry), Harness, Baseline
               tck/       AdapterTck — verifies the adapter before findings are believed
               report/    HTML / JSON / SARIF / JUnit XML
               examples/  ReferenceLedger (correct), NaiveLedger (deliberately ordinary)
               adapter/   HttpLedgerAdapter — universal, java.net.http only
               cli/       run | tck | demo
lck-junit5/  the ONLY module with a third-party compile dependency (junit-jupiter-api).
             Separate so CLI and library users never drag JUnit onto their classpath.
```

Everything inside `lck` is a **package**, not a module. Packages are free. A module costs
a build file, a POM, a version to keep in step, and a line in every client's dependency
review — so it has to earn its place.

## Adding an invariant

Required, in this order:

1. Add to `Invariants.REGISTRY` with an id, a severity, and a `productionSymptom` written
   as the incident an engineer would recognize — not a restatement of the title.
2. Add a **mutant** to `Mutants.all()`: the reference ledger with exactly one injected
   defect that this invariant catches.
3. `./gradlew :lck:test` must show the mutant tripping the new invariant
   (no false negatives) **and** the invariant passing `ReferenceLedger`
   (no false positives). Both assertions are in `MutationTest`.
4. If it needs a feature not every ledger has, gate it behind a `Capability`. An invariant
   that fails a ledger for lacking a feature is a bug in the invariant.
5. Note it in the release notes: a new invariant in a MINOR **can turn a client's build
   red**, which is why `Baseline` exists.

## Testing conventions

- `./gradlew verify` is what CI runs, on every push and pull request. Use it before saying
  anything is done. CI also re-runs the tests on a second seed and runs the demo.
- `./gradlew demo` runs the suite against both example ledgers. The naive one is
  **supposed** to fail — currently ten invariants, passing three and skipping one. That is a
  fixture, not a regression. Nothing pins the exact set yet, which is why the number drifted
  from eight; pin it before relying on it.
- Concurrency invariants use `Harness.burst(n, task)`, which releases every task from a
  latch simultaneously. Do not replace it with a plain executor: these defects live in a
  window of microseconds and a staggered submission misses them.
- There are no Testcontainers tests yet. If you add one, it must skip cleanly without
  Docker rather than fail.

## Style

- Comments explain **why**, never what. If a comment restates the code, delete it.
- Prefer records and `final` classes. No frameworks, no annotations beyond JUnit.
- Findings are read by people who think in currency: format with `Invariants.money()`.
- British-plain English in user-facing strings. No exclamation marks, no marketing voice.

## Roadmap awareness

Do not build v2 features into v1. Deferred deliberately: fault injection, deterministic
simulation with an interleaving scheduler, serializability checking, agentic mandate
invariants, native-image CLI. See `ROADMAP.md`. If a change would be easier
with one of those, say so rather than smuggling it in.

## This repository is public

Everything here is world-readable and git history is permanent — deleting a file in a later
commit does not remove it from the repo. Before writing anything into a tracked file, apply
one test: **would this read badly if a competitor, a client, or a journalist quoted it?**

Never write into this repository:

- Pricing, fees, deal terms, pipeline, or anything about who might buy
- Client names, findings, adapters, or any material from an engagement
- Marketing framing. Describe what the code does, not why someone should pay for it
- Article drafts or campaign plans

Commercial and strategic material lives in a separate private repository. `.gitignore`
blocks the common filenames as a backstop, but the backstop is not the control — the
control is not writing it here in the first place.

Statements of fact about the commercial practice are fine where a reader needs them:
that paid engagements exist, and that they follow `docs/publication-protocol.md`. One
sentence, factual, no persuasion.

## What not to do

- Do not add a dependency to a zero-dependency module to make something convenient.
- Do not weaken an invariant to make a client's ledger pass. The finding is the product.
- Do not add scoring, grading or a "compliance percentage". The kit reports what held; it
  does not certify.
- Do not run the suite against anything but a scratch environment. `AdapterTck` TCK-00
  refuses a non-empty one, and that check must never be relaxed or moved. It runs before
  any check that writes, and `AdapterTckTest` asserts both that ordering and that a refused
  environment is never reset.
