# Adapter guide

The adapter is the only code you write. Budget an afternoon.

Three routes, in order of how little work they are. **Read route 1 before route 2** —
most teams reach for the JVM route and would have been better served by HTTP.

---

## Route 1 — HTTP (any language, any stack)

Expose four test-only endpoints against a scratch environment. About 80 lines in any
language, and it works whether your ledger is in Go, Python, .NET, Rust, Node or Java.

```
POST /_lck/reset                                       -> 200
POST /_lck/post     {idempotencyKey, transactionId,
                     allowOverdraft, legs:[...]}       -> {status, transactionId, reason?}
GET  /_lck/balance  ?account=&currency=                -> {subunits}
GET  /_lck/journal                                     -> [entries]
```

Then:

```bash
lck run --adapter http://ledger-test.internal:8080 \
        --capabilities OVERDRAFT_GUARD,REPLAY
```

**Why this route is usually right.** It is black-box against a deployed environment, so it
exercises your connection pool, your load balancer and your real transaction boundaries.
An in-process test exercises none of those, and that is where several of these defects
actually live.

**Guard rails to build in.** Mount the endpoints behind a build profile that cannot exist
in production, and have the handler refuse to start unless the datasource name matches a
scratch pattern. Belt and braces; the suite writes.

Full request and response shapes: [`http-adapter.md`](http-adapter.md).

---

## Route 2 — JVM, in-process

If your ledger is a JVM library or a service you can construct in a test:

```java
public final class AcmeLedgerAdapter implements LedgerAdapter {

    private final AcmeLedger ledger = AcmeLedger.forSchema("lck_scratch");

    @Override public String name() { return "acme-ledger"; }

    @Override public Set<Capability> capabilities() {
        return EnumSet.of(Capability.OVERDRAFT_GUARD, Capability.REPLAY);
    }

    @Override public void reset() {
        ledger.truncateAll();                       // scratch schema only
    }

    @Override public PostResult post(Transaction txn) {
        try {
            var id = ledger.post(toAcme(txn));
            return PostResult.applied(id);
        } catch (DuplicateKeyException e) {         // your unique-constraint violation
            return PostResult.duplicate(e.existingTransactionId());
        } catch (InsufficientFundsException e) {    // a business outcome, not a crash
            return PostResult.rejected(txn.transactionId(), e.getMessage());
        }
    }

    @Override public long balance(String account, String currency) {
        return ledger.balanceMinorUnits(account, currency);   // subunits!
    }

    @Override public List<JournalEntry> journal() {
        return ledger.allEntries().stream().map(AcmeLedgerAdapter::toLck).toList();
    }
}
```

```java
@LedgerConformance(adapter = AcmeLedgerAdapter.class, seed = 42)
class LedgerConformanceTest extends ConformanceTests { }
```

Fourteen ordinary JUnit tests appear, named by invariant. Your existing CI reporting,
flaky-test history and IDE integration work with no further wiring.

---

## Route 3 — start from the worked Postgres adapter

A worked Postgres implementation (planned for v1.0, see ROADMAP.md) targets the schema against the schema in
`docs/02-architectures.md`: sharded balances, an idempotency table with a unique
constraint, a hash-chained journal. If your ledger is Postgres-backed, copying it and
changing the table names is often faster than writing from scratch.

---

## The five traps, in the order they actually bite

Every one of these is caught by the TCK. That is why the TCK exists — each produces a
plausible red scorecard that is entirely the adapter's fault, and defending a finding
that turns out to be your own wiring error is expensive.

**1. Major units instead of subunits.** `balance()` must return `12345` for £123.45.
Returning `123` is the single most common adapter bug. *(TCK-02)*

**2. Throwing on a business rejection.** Insufficient funds is a `PostResult` with status
`REJECTED`. If you throw, the suite cannot distinguish a correctly refused payment from a
crash, and INV-09 will report nonsense. *(TCK-04)*

**3. `reset()` that does not reset.** Point it at a scratch schema and truncate. If state
leaks between invariants the findings are meaningless. *(TCK-01, TCK-09)*

**4. `journal()` returning a live view or an unordered collection.** Return a snapshot, in
commit order. A `HashMap` iteration order will fail INV-02 and INV-14 for reasons that
have nothing to do with your ledger. *(TCK-05, TCK-07)*

**5. Over-declaring capabilities.** Declare only what the ledger genuinely has.
Under-declaring is safe — those invariants report *not applicable*. Over-declaring
produces false findings that waste your engineers' time and your credibility. *(TCK-08)*

---

## Verify the adapter before you believe the findings

```bash
lck tck --adapter com.acme.AcmeLedgerAdapter
```

Nine checks, all on behaviour every correct adapter must exhibit regardless of how the
underlying ledger works. If any fails, the runner suppresses conformance results rather
than reporting a scorecard it cannot stand behind.

Only then:

```bash
lck run --adapter com.acme.AcmeLedgerAdapter --seed 42
```

---

## Adopting on a ledger that already has failures

Do not switch on a gate that reddens the build on day one. It will be disabled by Friday
and then it is worth nothing.

```bash
lck run --adapter com.acme.AcmeLedgerAdapter --write-baseline lck-baseline.txt   # once
lck run --adapter com.acme.AcmeLedgerAdapter --baseline lck-baseline.txt          # in CI
```

CI now fails only on **new** failures. The baseline is a flat, sorted text file so a pull
request diff reads as "we fixed INV-06" with no tooling. Delete lines as you fix them; an
empty file is the goal.

---

## Reproducing a finding

Every result carries its seed, and every failure message carries a number:

```
INV-06  FAIL  BLOCKER  No lost updates on a hot account
        balance 3.00, expected 500.00 — lost 497.00
        reproduce: --seed 42
```

```bash
lck run --adapter com.acme.AcmeLedgerAdapter --seed 42
```

If a finding is not reproducible from its seed, that is a bug in the kit and worth
reporting — reproducibility is the property the whole tool rests on.
