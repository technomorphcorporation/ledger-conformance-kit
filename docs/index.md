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

> **Point it at a scratch environment.** The suite writes: it posts transactions, and it calls
> `reset()` before every invariant. `AdapterTck` check TCK-00 reads the journal before anything
> else runs and refuses to continue if it is not already empty — but that is a backstop, not a
> licence. Never run it against production.

```kotlin
// Gradle
testImplementation("io.github.technomorphcorporation:lck-junit5:1.0.0")
```

```xml
<!-- Maven -->
<dependency>
  <groupId>io.github.technomorphcorporation</groupId>
  <artifactId>lck-junit5</artifactId>
  <version>1.0.0</version>
  <scope>test</scope>
</dependency>
```

One coordinate. `lck-junit5` brings `lck-spi` with it — that is the artifact your adapter
implements, and it has **zero dependencies** and targets Java 17, so a team still on 17 can
write one. `lck-junit5` is the only module that pulls a third-party jar.

**Put the adapter in your test source set.** It is test infrastructure, and `reset()` empties
the ledger — code that can truncate a schema has no business on a production classpath. Keep it
in `src/test/java` and it cannot be reached from one. That is also what makes the single
coordinate enough: the test classpath is where `lck-junit5` puts `lck-spi`.

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

Declare only the capabilities your ledger genuinely has. An invariant that needs one you have
not declared reports **not applicable**, never a failure — a ledger with no overdraft concept
is not broken for lacking one.

Then one annotation. Note `extends`: `ConformanceTests` is an abstract class holding the
`@TestTemplate` that JUnit expands into one test per invariant.

```java
@LedgerConformance(adapter = AcmeLedgerAdapter.class, seed = 42)
class LedgerConformanceTest extends ConformanceTests { }
```

Fifteen ordinary JUnit tests appear: one `adapter TCK` check, then fourteen named by
invariant. Existing CI reporting, flaky-test history and IDE integration all work with no
further wiring.

Start with a stub and `adapter TCK` fails, listing what is missing — `balance()` in minor
units, a journal that grows, both legs recorded. The invariants are skipped while it does,
because a scorecard from an adapter nobody can trust is worse than no scorecard.

**Not on the JVM?** Expose four test-only HTTP endpoints and run the same suite against them —
about 80 lines in any language, and no Java to write. See
[the HTTP contract](http-adapter.md).

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
