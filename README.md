# Ledger Conformance Kit

**Fourteen executable invariants that any system moving money is expected to hold.
Point it at your ledger. It tells you, in cents, which ones you break.**

[![ci](https://github.com/technomorphcorporation/ledger-conformance-kit/actions/workflows/ci.yml/badge.svg)](https://github.com/technomorphcorporation/ledger-conformance-kit/actions)
[![Maven Central](https://img.shields.io/maven-central/v/com.technomorphcorporation.lck/lck-spi)](https://central.sonatype.com/artifact/com.technomorphcorporation.lck/lck-spi)
[![Apache 2.0](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

Most ledger defects survive code review, and they survive your test suite too — because
your test suite calls each method once, and these defects only exist under concurrency,
retry and partial failure. This runs the other cases.

---

## 60 seconds

```bash
git clone https://github.com/technomorphcorporation/ledger-conformance-kit
cd ledger-conformance-kit && ./gradlew demo
```

Two bundled ledgers. The reference implementation holds all fourteen. The other is
deliberately ordinary:

```
  naive-ledger   seed=42        # amounts below are drawn from the seed
  INV-04   FAIL   BLOCKER  Duplicate submission is a no-op          replay wrote 2 extra journal entries
  INV-05   FAIL   BLOCKER  Concurrent duplicates collapse to one    64 of 64 submissions of one key applied, expected at most 1
  INV-06   FAIL   BLOCKER  No lost updates on a hot account         balance 206.00 after 500 of 500 credits applied — lost 294.00
  INV-07   FAIL   BLOCKER  Value is conserved under transfers       total drifted -3.00 (open 800.00 -> close 797.00)
  INV-08   FAIL   BLOCKER  Balance equals the journal projection    acct:a: read path says 458.50, journal says 400.00
  INV-09   FAIL   BLOCKER  Overdraft guard holds under a drain      20 of 20 applied, drawing 200.00 against an opening 100.00
  INV-10   FAIL   BLOCKER  No precision drift over small movements  destination holds 999 subunits, expected 1000
  INV-11   FAIL   MAJOR    Currencies cannot be mixed               a USD debit was allowed to close an EUR credit
  INV-13   FAIL   MAJOR    State rebuilds from the event log        acct:0: live 209.00 vs rebuilt 200.00
  INV-14   FAIL   MINOR    Per-account ordering is monotonic        account acct:a has duplicate sequence numbers
  held 3 · broke 10 · not applicable 1
```

It validates double-entry, keeps an append-only journal, and checks for sufficient funds.
It is wrong anyway. The parts it gets right are the parts that are easy to test.

---

## Add it to your build

You compile against **one artifact with zero dependencies**, targeting **Java 17** so a
team still on 17 can implement an adapter.

```kotlin
testImplementation("com.technomorphcorporation.lck:lck-junit5:1.0.0")
```

Implement five methods:

```java
public final class AcmeLedgerAdapter implements LedgerAdapter {
    public String name()                            { return "acme-ledger"; }
    public Set<Capability> capabilities()           { return EnumSet.of(OVERDRAFT_GUARD, REPLAY); }
    public void reset()                             { /* truncate the scratch schema */ }
    public PostResult post(Transaction txn)         { /* commit or reject, idempotently */ }
    public long balance(String acct, String ccy)    { /* minor units */ }
    public List<JournalEntry> journal()             { /* append-only, in commit order */ }
}
```

Then one annotation:

```java
@LedgerConformance(adapter = AcmeLedgerAdapter.class, seed = 42)
class LedgerConformanceTest extends ConformanceTests { }
```

Fourteen ordinary JUnit tests appear, named by invariant. Your existing CI reporting,
flaky-test history and IDE integration all work with no further wiring.

**Not on the JVM?** Expose four test-only HTTP endpoints and run the same suite — about 80
lines in any language. See [`docs/adapter-guide.md`](docs/adapter-guide.md).

---

## Your adapter is tested first

Before any invariant runs, the **TCK** checks your adapter rather than your ledger: that
`reset()` really empties state, that `balance()` returns minor units and not dollars, that
`journal()` comes back in commit order, that a business rejection is a return value and not
an exception.

Every one of those mistakes produces a plausible red scorecard that is entirely the
adapter's fault. If the TCK fails, conformance results are suppressed.

```bash
lck tck --adapter com.acme.AcmeLedgerAdapter
```

---

## Adopting on a ledger that already fails

A gate that reddens the build on day one gets switched off by Friday.

```bash
lck run --adapter com.acme.AcmeLedgerAdapter --write-baseline lck-baseline.txt   # once
lck run --adapter com.acme.AcmeLedgerAdapter --baseline lck-baseline.txt          # in CI
```

CI now fails only on **new** failures. The baseline is a flat sorted text file, so a pull
request diff reads as "we fixed INV-06". Delete lines as you fix them.

---

## The fourteen invariants

| Ref | Invariant | Severity |
|---|---|---|
| INV-01 | Every transaction is balanced per currency | BLOCKER |
| INV-02 | The journal is append-only | BLOCKER |
| INV-03 | Hash chain is intact | MAJOR |
| INV-04 | Duplicate submission is a no-op | BLOCKER |
| INV-05 | Concurrent duplicates collapse to one | BLOCKER |
| INV-06 | No lost updates on a hot account | BLOCKER |
| INV-07 | Value is conserved under concurrent transfers | BLOCKER |
| INV-08 | Balance equals the journal projection | BLOCKER |
| INV-09 | Overdraft guard holds under a concurrent drain | BLOCKER |
| INV-10 | No precision drift over many small movements | BLOCKER |
| INV-11 | Currencies cannot be mixed inside one transaction | MAJOR |
| INV-12 | Reversal is compensation, never deletion | MAJOR |
| INV-13 | State rebuilds exactly from the event log | MAJOR |
| INV-14 | Per-account ordering is monotonic | MINOR |

Invariants requiring a capability your adapter does not declare report **not applicable**,
never failure. The kit has opinions about correctness, not about your feature set. Nothing is
assumed either: the HTTP adapter starts with no capabilities until you pass `--capabilities`,
so an undeclared feature is a skipped invariant rather than a red one.

The seed on every run controls the workload — the amounts each invariant moves and the
transaction ids it stamps — so a finding that names a transaction can be found again by
re-running with the same seed. It does not control thread interleaving, so a concurrency
finding may need more than one run to reappear; the kit says so rather than implying a
reproduction it cannot deliver.

A finding is also always measured against what your ledger *applied*, never against what was
submitted. A ledger that refuses a conflicting write — a serialization failure under
SERIALIZABLE, say — has lost nothing, and the suite says so rather than reporting the
shortfall as missing money. A run that cannot reach the ledger at all reports **unreachable**,
which is neither a pass nor a finding.

---

## How we know the invariants themselves are right

A suite has two failure modes, and the second is the one that ends a project's
credibility: passing a broken ledger, and **failing a correct one**.

Every push runs a mutation check, and so does every `./gradlew verify`:

1. **No false positives** — all fourteen must pass a known-correct ledger. An invariant
   that fails a correct implementation is a bug in the invariant.
2. **No false negatives** — a corpus of mutants, each the reference ledger with one
   injected defect, must each trip their named invariant.

```
M-01  OK  INV-05  idempotency claim is check-then-act instead of atomic
M-03  OK  INV-06  hot-account balance read-modify-written without lock
M-04  OK  INV-09  sufficient-funds check runs before the lock is taken
```

Each mutant declares the **exact** set of invariants it should break, and the build fails if
it breaks any others: a defect that reddens half the suite is not evidence for any single
invariant. Every invariant in the registry must appear in some mutant's expected set, so one
cannot ship on reasoning alone.

Where two invariants genuinely catch the same defect, the coupling is declared rather than
engineered around — INV-08 and INV-13 assert the same identity at different scales, and both
mutants that target it name both.

New invariants go through an RFC in [`rfcs/`](rfcs/) with a mutant demonstrating what they
catch.

---

## For your technology risk review

- **Zero runtime dependencies** in `lck-spi` and `lck` — asserted by
  `./gradlew complianceCheck`, not just claimed. Only `lck-junit5` pulls a third-party
  jar, and only if you use the JUnit integration
- **No network egress** except to the adapter endpoint you configure. No telemetry, no
  update check, no licence call. Runs air-gapped — and `complianceCheck` fails the build on
  any remote asset referenced by a report, so opening one fetches nothing
- **No production data** if you point it at a scratch environment. `journal()` returns what
  the ledger holds, so this is a deployment property; `AdapterTck` TCK-00 reads the journal
  before anything writes and refuses to continue if it is not already empty
- Apache-2.0, no copyleft in the tree; reproducible builds; Gradle wrapper pinned by
  SHA-256 and validated in CI; third-party GitHub Actions pinned to commit SHAs rather than
  mutable tags. Release signing is configured but nothing has been published yet — see
  COMPLIANCE.md for what is and is not in place

Full detail: [COMPLIANCE.md](COMPLIANCE.md) · [SECURITY.md](SECURITY.md)

---

## Scope, honestly

The kit tests **correctness properties of the money path**. It does not test performance,
security, or whether your accounting model matches your business — no tool can tell you
the last one. A clean run means your ledger held these properties on the day it ran.

There is no score, no grade and no percentage, deliberately. It is not a certification and
must not be represented as one.

**Never run it against production.** The suite writes.

---

## Documentation

- [`docs/adapter-guide.md`](docs/adapter-guide.md) — three routes in, and the five traps
- [`docs/http-adapter.md`](docs/http-adapter.md) — the language-agnostic contract
- [`docs/01-core-topics.md`](docs/01-core-topics.md) — hot-account concurrency, idempotency,
  event sourcing, sagas, money arithmetic, reconciliation
- [`docs/02-architectures.md`](docs/02-architectures.md) — three reference designs with DDL
- [`CLAUDE.md`](CLAUDE.md) · [`ROADMAP.md`](ROADMAP.md) · [`CONTRIBUTING.md`](CONTRIBUTING.md)

---

## Why this exists

Twenty years building ledgers, custody and brokerage platforms and regulatory reporting on
programmes for Goldman Sachs, UBS and Credit Suisse — systems where a wrong number was a
regulatory event rather than a bug ticket. The same failures appeared at every firm,
written by good engineers every time. This is those failures, made executable, so a team
can find them in an afternoon instead of at month-end close.

Apache-2.0. No signup, no gate, no telemetry. Run it, fork it, and publish whatever you
find — you owe nobody anything for using it.

Technomorph Corporation also runs paid conformance engagements. That work is governed by a
published [publication protocol](docs/publication-protocol.md), and it has no bearing on
this repository.

— Manjul Bhakri · [LinkedIn](https://www.linkedin.com/in/manjulbhakri/)
