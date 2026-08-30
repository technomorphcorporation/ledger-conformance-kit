package com.technomorph.lck.core;

import com.technomorph.lck.spi.LedgerAdapter;
import com.technomorph.lck.spi.Capability;
import com.technomorph.lck.spi.Model;
import com.technomorph.lck.spi.Model.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * The fourteen invariants.
 *
 * <p>Each one is an executable statement of something that must be true of any system
 * that moves money, ordered roughly by how expensive the failure is in production.
 *
 * <p>Three design rules, and they are what make findings arguable-with or not:
 * <ul>
 *   <li><b>Every failure reports a number.</b> Not "INV-06 failed" but "lost 498.00 of
 *       500.00". A number is a defect report; a boolean is an opinion.</li>
 *   <li><b>Every run is seeded.</b> The seed prints with the result and reproduces the
 *       same pressure. "Sometimes" is not a finding an engineer can act on.</li>
 *   <li><b>Concurrency uses a start barrier, not a thread pool.</b> These defects live in
 *       a window of microseconds. Submitting tasks to an executor staggers them and
 *       misses; releasing them all from a latch does not.</li>
 * </ul>
 */
public final class Invariants {

    public enum Severity { BLOCKER, MAJOR, MINOR }

    public enum Status { PASS, FAIL, SKIP, ERROR }

    public record Result(String id, String title, Severity severity, Status status,
                         String detail, String productionSymptom, long seed, long millis) {
        public boolean broke() { return status == Status.FAIL || status == Status.ERROR; }
    }

    public record Check(boolean held, String detail) {
        public static Check ok(String d)  { return new Check(true, d); }
        public static Check bad(String d) { return new Check(false, d); }
    }

    @FunctionalInterface
    public interface Body { Check run(LedgerAdapter led, Harness h) throws Exception; }

    public record Invariant(String id, String title, Severity severity,
                            String productionSymptom, Capability requires, Body body) {}

    // ================================================================= harness

    /** All non-determinism goes through here, seeded, so a failure is reproducible. */
    public static final class Harness {

        /** Long enough for a slow remote ledger under 500-way concurrency; short enough to fail CI. */
        private static final int BURST_TIMEOUT_SECONDS = 180;

        private final long seed;
        private final Random rnd;

        Harness(long seed) { this.seed = seed; this.rnd = new Random(seed); }

        public long seed() { return seed; }
        public Random random() { return rnd; }

        /**
         * Release n tasks simultaneously. Virtual threads, so n can be large without
         * pool-sizing games, and a CountDownLatch so they genuinely go at once.
         */
        public void burst(int n, IntConsumer task) throws Exception {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(n);
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
            // Deliberately not try-with-resources. ExecutorService.close() waits for
            // termination without a bound, so a task blocked on the ledger under test would
            // swallow the timeout below during the unwind and hang the run forever — against
            // a deadlocked ledger, which is precisely the system this suite gets pointed at.
            ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
            try {
                for (int i = 0; i < n; i++) {
                    final int idx = i;
                    exec.submit(() -> {
                        try {
                            start.await();
                            task.accept(idx);
                        } catch (Throwable t) {
                            errors.add(t);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                if (!done.await(BURST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    throw new TimeoutException("burst of " + n + " did not complete in "
                            + BURST_TIMEOUT_SECONDS + "s; " + (n - done.getCount()) + " of " + n
                            + " finished, " + done.getCount() + " still in flight");
            } finally {
                exec.shutdownNow();
            }
            if (!errors.isEmpty()) {
                Throwable first = errors.get(0);
                throw new IllegalStateException(errors.size() + " of " + n
                        + " submissions threw; first was " + first.getClass().getSimpleName()
                        + ": " + first.getMessage(), first);
            }
        }
    }

    // ================================================================= registry

    public static final List<Invariant> REGISTRY = List.of(

        // ------------------------------------------------------------ structural

        new Invariant("INV-01", "Every transaction is balanced per currency", Severity.BLOCKER,
            "Money is created or destroyed on write. Your trial balance never ties out.", null,
            (led, h) -> {
                led.seed("acct:a", 10_000);
                led.post(Transaction.transfer("acct:a", "acct:b", 4_000, "t1"));
                Map<String, Map<String, Long>> byTxn = new LinkedHashMap<>();
                for (JournalEntry e : led.journal())
                    byTxn.computeIfAbsent(e.transactionId(), k -> new HashMap<>())
                         .merge(e.currency(), e.signed(), Long::sum);
                List<String> bad = byTxn.entrySet().stream()
                        .filter(en -> en.getValue().values().stream().anyMatch(v -> v != 0L))
                        .map(Map.Entry::getKey).toList();
                return bad.isEmpty()
                        ? Check.ok(byTxn.size() + " transactions, all net zero per currency")
                        : Check.bad(bad.size() + " unbalanced transaction(s), first: " + bad.get(0));
            }),

        new Invariant("INV-02", "The journal is append-only", Severity.BLOCKER,
            "History is rewritable, so no auditor and no regulator can trust your books.", null,
            (led, h) -> {
                led.seed("acct:a", 10_000);
                led.post(Transaction.transfer("acct:a", "acct:b", 1_000, "t1"));
                List<String> before = fingerprint(led.journal());
                led.post(Transaction.transfer("acct:b", "acct:a", 300, "t2"));
                led.post(Transaction.transfer("acct:a", "acct:b", 100, "t3"));
                List<String> after = fingerprint(led.journal());
                if (after.size() <= before.size())
                    return Check.bad("journal held at " + after.size() + " entries after 2 further "
                            + "transactions, expected at least " + (before.size() + 1));
                int i = 0;
                while (i < before.size() && after.get(i).equals(before.get(i))) i++;
                return i == before.size()
                        ? Check.ok("prefix preserved across " + after.size() + " entries")
                        : Check.bad("prefix diverged at entry " + i + " of " + before.size()
                                + " — an earlier entry was mutated, reordered or dropped");
            }),

        new Invariant("INV-03", "Hash chain is intact", Severity.MAJOR,
            "You cannot prove to an auditor that a row was not edited in the database.",
            Capability.HASH_CHAIN,
            (led, h) -> {
                led.seed("acct:a", 10_000);
                for (int i = 0; i < 5; i++)
                    led.post(Transaction.transfer("acct:a", "acct:b", 100, "t" + i));
                String prev = "GENESIS";
                for (JournalEntry e : led.journal()) {
                    String expected = Model.hashEntry(prev, e.transactionId(), e.accountId(),
                            e.type().name(), e.amountSubunits(), e.currency(), e.sequence());
                    if (!prev.equals(e.prevHash()))
                        return Check.bad("prevHash mismatch at sequence " + e.sequence());
                    if (!expected.equals(e.entryHash()))
                        return Check.bad("entryHash does not verify at sequence " + e.sequence());
                    prev = e.entryHash();
                }
                return Check.ok(led.journal().size() + " entries chained from genesis");
            }),

        // ----------------------------------------------------------- idempotency

        new Invariant("INV-04", "Duplicate submission is a no-op", Severity.BLOCKER,
            "The retry your client library performs on a timeout charges the customer twice.", null,
            (led, h) -> {
                led.seed("acct:a", 10_000);
                led.post(Transaction.transfer("acct:a", "acct:b", 2_500, "dup-key"));
                int n1 = led.journal().size();
                PostResult r2 = led.post(Transaction.transfer("acct:a", "acct:b", 2_500, "dup-key"));
                int n2 = led.journal().size();
                long bal = led.balance("acct:b");
                if (n2 != n1)
                    return Check.bad("replay wrote " + (n2 - n1) + " extra journal entries");
                if (bal != 2_500)
                    return Check.bad("balance " + money(bal) + " after replay, expected 25.00");
                if (r2.status() != PostStatus.DUPLICATE)
                    return Check.bad("replay returned " + r2.status() + ", expected DUPLICATE — "
                            + "the ledger deduplicated but did not say so, and the caller cannot tell");
                return Check.ok("replay recognised as DUPLICATE, balance held at " + money(bal));
            }),

        new Invariant("INV-05", "Concurrent duplicates collapse to one", Severity.BLOCKER,
            "A double-clicked button or a load-balanced retry lands twice in the same millisecond.", null,
            (led, h) -> {
                led.seed("acct:a", 100_000);
                AtomicInteger applied = new AtomicInteger();
                h.burst(64, i -> {
                    try {
                        if (led.post(Transaction.transfer("acct:a", "acct:b", 2_500, "race-key"))
                                .status() == PostStatus.APPLIED) applied.incrementAndGet();
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                long bal = led.balance("acct:b");
                return applied.get() == 1 && bal == 2_500
                        ? Check.ok("exactly one of 64 simultaneous submissions applied")
                        : Check.bad(applied.get() + " of 64 simultaneous submissions applied; balance "
                                + money(bal) + " (expected 1 and 25.00)");
            }),

        // ----------------------------------------------------------- concurrency

        new Invariant("INV-06", "No lost updates on a hot account", Severity.BLOCKER,
            "The busiest merchant, wallet or omnibus account is the one that silently loses money.", null,
            (led, h) -> {
                final int n = 500, amt = 100;
                h.burst(n, i -> {
                    try {
                        led.post(new Transaction("hot-" + i,
                                List.of(Leg.debit("external:funding", amt), Leg.credit("acct:hot", amt)),
                                UUID.randomUUID().toString(), true, Map.of()));
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                long bal = led.balance("acct:hot"), expected = (long) n * amt;
                return bal == expected
                        ? Check.ok(n + " simultaneous credits, balance exact at " + money(bal))
                        : Check.bad("balance " + money(bal) + ", expected " + money(expected)
                                + " — lost " + money(expected - bal));
            }),

        new Invariant("INV-07", "Value is conserved under concurrent transfers", Severity.BLOCKER,
            "Money leaks between accounts under load and nobody notices until month-end close.", null,
            (led, h) -> {
                final int accounts = 8, moves = 400;
                for (int i = 0; i < accounts; i++) led.seed("acct:" + i, 10_000);
                long opening = 0;
                for (int i = 0; i < accounts; i++) opening += led.balance("acct:" + i);
                h.burst(moves, i -> {
                    try {
                        led.post(Transaction.transfer("acct:" + (i % accounts),
                                "acct:" + ((i + 3) % accounts), 100, "mv-" + i, true));
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                long closing = 0;
                for (int i = 0; i < accounts; i++) closing += led.balance("acct:" + i);
                return closing == opening
                        ? Check.ok("total held constant at " + money(closing)
                                + " across " + moves + " concurrent transfers")
                        : Check.bad("total drifted " + (closing > opening ? "+" : "")
                                + money(closing - opening) + " (open " + money(opening)
                                + " -> close " + money(closing) + ")");
            }),

        new Invariant("INV-08", "Balance equals the journal projection", Severity.BLOCKER,
            "Your cached or denormalised balance and your books disagree. Both are quoted to customers.",
            null,
            (led, h) -> {
                led.seed("acct:a", 50_000);
                h.burst(200, i -> {
                    try {
                        led.post(Transaction.transfer("acct:a", "acct:b", 50, "proj-" + i, true));
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                StringBuilder bad = new StringBuilder();
                for (String a : new String[]{"acct:a", "acct:b"}) {
                    long reported = led.balance(a), projected = led.replayBalance(a, "USD");
                    if (reported != projected)
                        bad.append(a).append(": read path says ").append(money(reported))
                           .append(", journal says ").append(money(projected)).append("; ");
                }
                return bad.length() == 0
                        ? Check.ok("read path and journal agree on every account")
                        : Check.bad(bad.toString().trim());
            }),

        new Invariant("INV-09", "Overdraft guard holds under a concurrent drain", Severity.BLOCKER,
            "Classic double-spend: twenty withdrawals check the same balance before any of them commits.",
            Capability.OVERDRAFT_GUARD,
            (led, h) -> {
                led.seed("acct:a", 10_000);
                AtomicInteger applied = new AtomicInteger();
                h.burst(20, i -> {
                    try {
                        if (led.post(Transaction.transfer("acct:a", "acct:b", 1_000, "drain-" + i))
                                .status() == PostStatus.APPLIED) applied.incrementAndGet();
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                long bal = led.balance("acct:a");
                if (bal < 0)
                    return Check.bad("account overdrew to " + money(bal)
                            + " — the guard was evaluated before the write, not with it");
                return applied.get() == 10 && bal == 0
                        ? Check.ok("exactly 10 of 20 withdrawals succeeded; balance floored at 0.00")
                        : Check.bad(applied.get() + " withdrawals applied, closing balance "
                                + money(bal) + " (expected 10 and 0.00)");
            }),

        // --------------------------------------------------------- money algebra

        new Invariant("INV-10", "No precision drift over many small movements", Severity.BLOCKER,
            "Floating point pennies. The number is right for months and then it isn't, "
            + "and you cannot say when it stopped being right.", null,
            (led, h) -> {
                led.seed("acct:a", 100_000);
                for (int i = 0; i < 1000; i++)
                    led.post(Transaction.transfer("acct:a", "acct:b", 1, "penny-" + i));
                long dst = led.balance("acct:b"), src = led.balance("acct:a");
                if (dst != 1000)
                    return Check.bad("after 1000 one-subunit transfers the destination holds "
                            + dst + " subunits, expected 1000 — drift of " + (1000 - dst));
                if (src != 99_000)
                    return Check.bad("source holds " + src + " subunits, expected 99000");
                return Check.ok("1000 one-subunit transfers, both sides exact");
            }),

        new Invariant("INV-11", "Currencies cannot be mixed inside one transaction", Severity.MAJOR,
            "A USD debit closes an EUR credit. The FX gap is booked nowhere and surfaces "
            + "later as unexplained P&L.", null,
            (led, h) -> {
                led.seed("acct:a", 10_000);
                PostResult r = led.post(new Transaction("fx-bad",
                        List.of(new Leg("acct:a", EntryType.DEBIT, 1_000, "USD"),
                                new Leg("acct:b", EntryType.CREDIT, 1_000, "EUR")),
                        UUID.randomUUID().toString(), true, Map.of()));
                return r.status() == PostStatus.REJECTED
                        ? Check.ok("cross-currency transaction with no FX leg was rejected")
                        : Check.bad("cross-currency transaction returned " + r.status()
                                + " — a USD debit was allowed to close an EUR credit");
            }),

        // -------------------------------------------------------------- recovery

        new Invariant("INV-12", "Reversal is compensation, never deletion", Severity.MAJOR,
            "You cannot show an auditor what actually happened, only what you chose to keep.",
            Capability.COMPENSATION,
            (led, h) -> {
                led.seed("acct:a", 10_000);
                led.post(Transaction.transfer("acct:a", "acct:b", 3_000, "orig"));
                List<String> original = fingerprint(led.journal());
                led.post(Transaction.transfer("acct:b", "acct:a", 3_000, "comp-of-orig"));
                List<String> after = fingerprint(led.journal());
                if (!after.containsAll(original))
                    return Check.bad("compensation removed or rewrote the original entries");
                if (after.size() != original.size() + 2)
                    return Check.bad("expected 2 compensating entries, saw "
                            + (after.size() - original.size()));
                long net = led.balance("acct:b");
                return net == 0
                        ? Check.ok("original and compensating entries both retained; net position zero")
                        : Check.bad("net position after compensation is " + money(net) + ", expected 0.00");
            }),

        new Invariant("INV-13", "State rebuilds exactly from the event log", Severity.MAJOR,
            "After a crash or a region failover you cannot reconstruct balances, "
            + "so you restore from a guess.", Capability.REPLAY,
            (led, h) -> {
                final int accounts = 6;
                for (int i = 0; i < accounts; i++) led.seed("acct:" + i, 20_000);
                h.burst(300, i -> {
                    try {
                        led.post(Transaction.transfer("acct:" + (i % accounts),
                                "acct:" + ((i + 2) % accounts), 300, "rb-" + i, true));
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                StringBuilder bad = new StringBuilder();
                for (int i = 0; i < accounts; i++) {
                    String a = "acct:" + i;
                    long live = led.balance(a), rebuilt = led.replayBalance(a, "USD");
                    if (live != rebuilt)
                        bad.append(a).append(": live ").append(money(live))
                           .append(" vs rebuilt ").append(money(rebuilt)).append("; ");
                }
                return bad.length() == 0
                        ? Check.ok("all " + accounts + " accounts rebuilt to the cent from the journal")
                        : Check.bad(bad.toString().trim());
            }),

        new Invariant("INV-14", "Per-account ordering is monotonic", Severity.MINOR,
            "You cannot answer 'what was the balance at 14:02' — the classic dispute "
            + "and regulatory question.", null,
            (led, h) -> {
                led.seed("acct:a", 50_000);
                h.burst(150, i -> {
                    try {
                        led.post(Transaction.transfer("acct:a", "acct:b", 100, "seq-" + i, true));
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                Map<String, List<Long>> seqs = new LinkedHashMap<>();
                for (JournalEntry e : led.journal())
                    seqs.computeIfAbsent(e.accountId(), k -> new ArrayList<>()).add(e.accountSequence());
                for (Map.Entry<String, List<Long>> en : seqs.entrySet()) {
                    List<Long> s = en.getValue();
                    if (new HashSet<>(s).size() != s.size())
                        return Check.bad("account " + en.getKey() + " has duplicate sequence numbers");
                    List<Long> sorted = new ArrayList<>(s);
                    Collections.sort(sorted);
                    if (!sorted.equals(s))
                        return Check.bad("account " + en.getKey() + " sequence numbers are out of order");
                }
                return Check.ok("strict per-account sequencing across " + seqs.size() + " accounts");
            })
    );

    // ================================================================== runner

    public static List<Result> run(LedgerAdapter led, long seed) {
        List<Result> out = new ArrayList<>(REGISTRY.size());
        for (Invariant inv : REGISTRY) out.add(runOne(led, inv, seed));
        return out;
    }

    /**
     * One invariant, including its {@code reset()}. Public so a caller that wants a single
     * invariant does not have to run the other thirteen against a client's ledger to get it —
     * which is what the JUnit integration needs in order to keep the whole suite out of
     * test <em>discovery</em>.
     */
    public static Result runOne(LedgerAdapter led, Invariant inv, long seed) {
        if (inv.requires() != null && !led.supports(inv.requires()))
            return new Result(inv.id(), inv.title(), inv.severity(), Status.SKIP,
                    "adapter does not declare " + inv.requires(), inv.productionSymptom(), seed, 0);
        long t0 = System.nanoTime();
        try {
            led.reset();
            Check c = inv.body().run(led, new Harness(seed));
            return new Result(inv.id(), inv.title(), inv.severity(),
                    c.held() ? Status.PASS : Status.FAIL, c.detail(),
                    inv.productionSymptom(), seed, ms(t0));
        } catch (Throwable t) {
            // An exception under concurrent load is itself a finding, not a harness bug.
            return new Result(inv.id(), inv.title(), inv.severity(), Status.ERROR,
                    t.getClass().getSimpleName() + ": " + t.getMessage(),
                    inv.productionSymptom(), seed, ms(t0));
        }
    }

    public static int blockerFailures(List<Result> results) {
        return (int) results.stream()
                .filter(r -> r.broke() && r.severity() == Severity.BLOCKER).count();
    }

    // ================================================================== helpers

    private static List<String> fingerprint(List<JournalEntry> j) {
        List<String> out = new ArrayList<>(j.size());
        for (JournalEntry e : j)
            out.add(e.transactionId() + "|" + e.accountId() + "|" + e.type() + "|" + e.amountSubunits());
        return out;
    }

    private static long ms(long t0) { return (System.nanoTime() - t0) / 1_000_000; }

    /** Findings are read by people who think in currency, not subunits. */
    public static String money(long subunits) {
        return String.format(Locale.ROOT, "%.2f", subunits / 100.0);
    }

    private Invariants() {}
}
