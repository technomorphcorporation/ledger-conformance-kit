package com.technomorph.lck.core;

import com.technomorph.lck.spi.LedgerAdapter;
import com.technomorph.lck.spi.Capability;
import com.technomorph.lck.spi.Model;
import com.technomorph.lck.spi.Model.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
 *   <li><b>Every run is seeded.</b> The seed prints with the result, so a finding names the
 *       conditions that produced it. "Sometimes" is not a finding an engineer can act on.
 *       <b>Not yet true in the way it should be:</b> the corpus is currently fixed — no
 *       invariant draws from {@link Harness#random()}, so varying the seed varies nothing.
 *       Either the invariants start drawing their pressure from it or the seed comes off the
 *       public surface; until then, treat the printed seed as a label, not a reproduction
 *       recipe.</li>
 *   <li><b>Concurrency uses a start barrier, not a thread pool.</b> These defects live in
 *       a window of microseconds. Submitting tasks to an executor staggers them and
 *       misses; releasing them all from a latch does not.</li>
 * </ul>
 */
public final class Invariants {

    public enum Severity { BLOCKER, MAJOR, MINOR }

    /**
     * INFRA is not a finding. It means the run could not reach the ledger — a dropped
     * connection, a timeout, a 502 from something in front of it. Reporting that as a defect
     * in the client's ledger is the fastest way to lose the argument about every other
     * finding on the page, so it is neither a pass nor a break.
     */
    public enum Status { PASS, FAIL, SKIP, ERROR, INFRA }

    public record Result(String id, String title, Severity severity, Status status,
                         String detail, String productionSymptom, long seed, long millis) {
        public boolean broke() { return status == Status.FAIL || status == Status.ERROR; }
    }

    public record Check(boolean held, String detail) {
        public static Check ok(String d)  { return new Check(true, d); }
        public static Check bad(String d) { return new Check(false, d); }
    }

    /**
     * What the ledger said, counted.
     *
     * <p>An invariant that submits n transactions and then asserts on n is asserting that the
     * ledger accepted all of them, which is a throughput claim wearing a correctness costume.
     * A ledger at SERIALIZABLE isolation returning REJECTED on a write conflict — Postgres
     * 40001, entirely correct — loses no money, and telling its authors it lost 20.00 ends the
     * conversation. Assert against what was applied; report the rest as observation.
     */
    public static final class Tally {
        private final AtomicInteger applied = new AtomicInteger();
        private final AtomicInteger duplicate = new AtomicInteger();
        private final AtomicInteger rejected = new AtomicInteger();
        private final AtomicReference<String> firstReason = new AtomicReference<>();

        public PostResult record(PostResult r) {
            switch (r.status()) {
                case APPLIED   -> applied.incrementAndGet();
                case DUPLICATE -> duplicate.incrementAndGet();
                case REJECTED  -> {
                    rejected.incrementAndGet();
                    firstReason.compareAndSet(null, r.reason());
                }
            }
            return r;
        }

        public int applied()   { return applied.get(); }
        public int duplicate() { return duplicate.get(); }
        public int rejected()  { return rejected.get(); }

        /** Empty when everything applied, so it can be appended to a passing message unconditionally. */
        public String note() {
            if (rejected.get() == 0 && duplicate.get() == 0) return "";
            StringBuilder sb = new StringBuilder(" (");
            if (rejected.get() > 0) {
                sb.append(rejected.get()).append(" rejected");
                if (firstReason.get() != null) sb.append(": ").append(firstReason.get());
            }
            if (duplicate.get() > 0)
                sb.append(rejected.get() > 0 ? ", " : "").append(duplicate.get()).append(" duplicate");
            return sb.append(')').toString();
        }
    }

    @FunctionalInterface
    public interface Body { Check run(LedgerAdapter led, Harness h) throws Exception; }

    public record Invariant(String id, String title, Severity severity,
                            String productionSymptom, Capability requires, Body body) {}

    // ================================================================= harness

    /**
     * Where all non-determinism is meant to go, seeded, so a failure is reproducible.
     *
     * <p>{@link #random()} has no callers today: every invariant uses fixed amounts, accounts
     * and iteration counts, and the ids that do vary come from {@code UUID.randomUUID()} in
     * the SPI rather than from here. So the seed is threaded through and reported but changes
     * nothing about what runs. Note also that {@link Random} shared across a {@link #burst}
     * would be thread-safe but not deterministic — draws would interleave differently each
     * run — so making the seed real means deriving a per-task sequence, not handing this
     * instance to concurrent tasks.
     */
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
            List<Throwable> collected;
            synchronized (errors) { collected = List.copyOf(errors); }
            if (!collected.isEmpty()) {
                // An Error raised inside a task belongs to the harness or the JVM, not to the
                // ledger. Wrapping it would launder an OutOfMemoryError into a BLOCKER finding
                // against someone else's system, so it leaves unchanged.
                for (Throwable t : collected) if (t instanceof Error e) throw e;
                Throwable first = collected.get(0);
                throw new IllegalStateException(collected.size() + " of " + n
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
                final int n = 64, amt = 2_500;
                led.seed("acct:a", 100_000);
                Tally t = new Tally();
                h.burst(n, i -> {
                    try {
                        t.record(led.post(Transaction.transfer("acct:a", "acct:b", amt, "race-key")));
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                long bal = led.balance("acct:b"), expected = (long) t.applied() * amt;
                if (t.applied() > 1)
                    return Check.bad(t.applied() + " of " + n + " submissions of one idempotency key "
                            + "applied, expected at most 1 — the key was claimed " + t.applied()
                            + " times, and the customer is charged " + money(bal));
                if (t.applied() == 0)
                    return Check.bad("none of " + n + " submissions applied" + t.note()
                            + " — the key held, but a valid funded transfer was never posted");
                if (bal != expected)
                    return Check.bad("1 submission applied but the balance is " + money(bal)
                            + ", expected " + money(expected));
                return Check.ok("exactly one of " + n + " simultaneous submissions applied, "
                        + "balance " + money(bal) + t.note());
            }),

        // ----------------------------------------------------------- concurrency

        new Invariant("INV-06", "No lost updates on a hot account", Severity.BLOCKER,
            "The busiest merchant, wallet or omnibus account is the one that silently loses money.", null,
            (led, h) -> {
                final int n = 500, amt = 100;
                Tally t = new Tally();
                h.burst(n, i -> {
                    try {
                        t.record(led.post(new Transaction("hot-" + i,
                                List.of(Leg.debit("external:funding", amt), Leg.credit("acct:hot", amt)),
                                UUID.randomUUID().toString(), true, Map.of())));
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                // Against what was applied, not against what was submitted: a refused write posts
                // nothing and loses nothing, and calling that a lost update is a false finding.
                long bal = led.balance("acct:hot"), expected = (long) t.applied() * amt;
                if (bal != expected)
                    return Check.bad("balance " + money(bal) + " after " + t.applied() + " of " + n
                            + " credits applied, expected " + money(expected) + " — lost "
                            + money(expected - bal) + t.note());
                if (t.applied() == 0)
                    return Check.bad("none of " + n + " credits applied" + t.note()
                            + " — nothing was lost because nothing was written");
                return Check.ok(t.applied() + " of " + n + " simultaneous credits applied, "
                        + "balance exact at " + money(bal) + t.note());
            }),

        new Invariant("INV-07", "Value is conserved under concurrent transfers", Severity.BLOCKER,
            "Money leaks between accounts under load and nobody notices until month-end close.", null,
            (led, h) -> {
                final int accounts = 8, moves = 400;
                for (int i = 0; i < accounts; i++) led.seed("acct:" + i, 10_000);
                long opening = 0;
                for (int i = 0; i < accounts; i++) opening += led.balance("acct:" + i);
                Tally t = new Tally();
                h.burst(moves, i -> {
                    try {
                        t.record(led.post(Transaction.transfer("acct:" + (i % accounts),
                                "acct:" + ((i + 3) % accounts), 100, "mv-" + i, true)));
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                // Conservation holds whatever the ledger accepted — a rejected transfer moves
                // nothing — so the applied count is reported, not asserted on.
                long closing = 0;
                for (int i = 0; i < accounts; i++) closing += led.balance("acct:" + i);
                return closing == opening
                        ? Check.ok("total held constant at " + money(closing)
                                + " across " + t.applied() + " of " + moves
                                + " concurrent transfers" + t.note())
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
                final int n = 20, amt = 1_000, opening = 10_000;
                led.seed("acct:a", opening);
                Tally t = new Tally();
                h.burst(n, i -> {
                    try {
                        t.record(led.post(Transaction.transfer("acct:a", "acct:b", amt, "drain-" + i)));
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                long bal = led.balance("acct:a"), expected = opening - (long) t.applied() * amt;

                // Safety, then the accounting identity. Deliberately NOT "exactly ten succeeded":
                // how many a ledger lets through under contention is a throughput property, and a
                // conservative ledger that applies seven is correct. Only two things must hold —
                // the account never goes negative, and the balance equals what was applied.
                if (bal < 0)
                    return Check.bad("account overdrew to " + money(bal) + " after " + t.applied()
                            + " of " + n + " withdrawals — the guard was evaluated before the "
                            + "write, not with it");
                long drawn = (long) t.applied() * amt;
                if (drawn > opening)
                    return Check.bad(t.applied() + " of " + n + " withdrawals applied, drawing "
                            + money(drawn) + " against an opening balance of " + money(opening)
                            + " — the guard allowed " + money(drawn - opening) + " more than the "
                            + "account could fund, and the reported balance of " + money(bal)
                            + " hides it");
                if (bal != expected)
                    return Check.bad(t.applied() + " withdrawals applied but the balance is "
                            + money(bal) + ", expected " + money(expected)
                            + " — the guard and the ledger disagree by " + money(bal - expected));
                if (t.applied() == 0)
                    return Check.bad("none of " + n + " withdrawals applied against a funded "
                            + "account" + t.note() + " — the guard refused everything");
                return Check.ok(t.applied() + " of " + n + " withdrawals applied, balance "
                        + money(bal) + ", never negative" + t.note());
            }),

        // --------------------------------------------------------- money algebra

        new Invariant("INV-10", "No precision drift over many small movements", Severity.BLOCKER,
            "Floating point pennies. The number is right for months and then it isn't, "
            + "and you cannot say when it stopped being right.", null,
            (led, h) -> {
                final int n = 1000, opening = 100_000;
                led.seed("acct:a", opening);
                Tally t = new Tally();
                for (int i = 0; i < n; i++)
                    t.record(led.post(Transaction.transfer("acct:a", "acct:b", 1, "penny-" + i)));
                long dst = led.balance("acct:b"), src = led.balance("acct:a");
                // A rate-limited or conflicted endpoint rejects posts; attributing that to
                // floating point would be a confident, numeric misdiagnosis.
                if (t.applied() == 0)
                    return Check.bad("none of " + n + " one-subunit transfers applied" + t.note());
                if (dst != t.applied())
                    return Check.bad("after " + t.applied() + " of " + n + " one-subunit transfers "
                            + "applied, the destination holds " + dst + " subunits, expected "
                            + t.applied() + " — drift of " + (t.applied() - dst) + t.note());
                if (src != opening - t.applied())
                    return Check.bad("source holds " + src + " subunits, expected "
                            + (opening - t.applied()) + " after " + t.applied() + " transfers");
                return Check.ok(t.applied() + " of " + n + " one-subunit transfers applied, "
                        + "both sides exact" + t.note());
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
        } catch (Error e) {
            // OutOfMemoryError in the harness is not a defect in the client's ledger. Let it out.
            throw e;
        } catch (Throwable t) {
            // An exception from the ledger under concurrent load is itself a finding. An
            // exception from the network in front of it is not, and the two must not print
            // the same way.
            return new Result(inv.id(), inv.title(), inv.severity(),
                    transportFailure(t) ? Status.INFRA : Status.ERROR,
                    describe(t), inv.productionSymptom(), seed, ms(t0));
        }
    }

    /**
     * True when the run could not reach the ledger, rather than the ledger behaving badly.
     *
     * <p>Detected by {@link java.io.IOException} anywhere in the cause chain: it is what the
     * HTTP adapter raises for a dropped connection or a 5xx, and it is what any adapter over a
     * socket raises naturally. Business rejection cannot be confused with it, because TCK-04
     * already refuses an adapter that throws instead of returning REJECTED.
     */
    private static boolean transportFailure(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause())
            if (c instanceof java.io.IOException) return true;
        return false;
    }

    /** Rule 5 applies to crashes too: name the frame, so the detail carries a line number. */
    private static String describe(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String msg = root.getMessage();
        StackTraceElement[] frames = root.getStackTrace();
        return root.getClass().getSimpleName()
                + (msg == null ? "" : ": " + msg)
                + (frames.length == 0 ? "" : " at " + frames[0]);
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
