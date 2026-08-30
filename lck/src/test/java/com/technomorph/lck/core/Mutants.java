package com.technomorph.lck.core;

import com.technomorph.lck.examples.ReferenceLedger;
import com.technomorph.lck.spi.Capability;
import com.technomorph.lck.spi.LedgerAdapter;
import com.technomorph.lck.spi.Model;
import com.technomorph.lck.spi.Model.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The mutant corpus. This is how the suite is vetted.
 *
 * <p>An invariant suite has two failure modes, and both are worse than having no suite:
 * <ul>
 *   <li><b>False negative</b> — the invariant passes against a system that is genuinely
 *       broken. The client ships with a clean scorecard and loses money anyway.</li>
 *   <li><b>False positive</b> — the invariant fails against a system that is correct.
 *       One of these destroys the practice, because the client's engineers will find it
 *       and they will be right.</li>
 * </ul>
 *
 * <p>Each mutant below is the reference ledger with exactly one defect injected, and the
 * build asserts that the mutant's named invariant <em>fails</em>.
 *
 * <p>Every invariant in the registry appears in some mutant's expected set — {@code
 * MutationTest.everyInvariantIsJustifiedByAMutant} fails the build otherwise — and each mutant
 * asserts its <em>exact</em> set, so a defect that reddens half the suite cannot pass as
 * evidence for one invariant.
 *
 * <p>Enforcing that exactness immediately found two mutants that were not modelling their own
 * names. M-05 scaled every balance by 0.99999999, which is not a double defect but an arbitrary
 * one, and tripped five invariants; it now accumulates in {@code double} and trips only INV-10.
 * M-06 replaced the balance read entirely and tripped nine; it now drops a journal leg, as its
 * name always said. A third, M-12, turned out to inject nothing at all — it matched idempotency
 * keys against transaction ids — and had been passing the old containment assertion because
 * INV-12 was failing for an unrelated reason.
 *
 * <p>The other half of the vetting is {@code noFalsePositives}: every invariant must pass the
 * reference ledger. An invariant that fails a known-good implementation is a bug in the
 * invariant, not a finding, and it is caught here rather than in front of a client.
 */
public final class Mutants {

    /**
     * A mutant, and the exact set of invariants it is expected to break.
     *
     * <p>The set, not just the first entry: asserting only that the named invariant broke lets a
     * mutant with a wide blast radius pass, and specificity — a finding attributable to one
     * defect rather than to general malaise — is the property that makes a report worth reading.
     * Where a defect legitimately trips more than one invariant, every id is named here, which
     * also documents the coupling.
     */
    public record Mutant(String id, String defect, Set<String> expected,
                         java.util.function.Supplier<LedgerAdapter> factory) {

        Mutant(String id, String defect, String expected,
               java.util.function.Supplier<LedgerAdapter> factory) {
            this(id, defect, Set.of(expected), factory);
        }

        /** The invariant this mutant exists to justify: the first one, in id order. */
        public String primary() { return expected.stream().sorted().findFirst().orElseThrow(); }
    }

    public static List<Mutant> all() {
        return List.of(
            new Mutant("M-01", "idempotency claim is check-then-act instead of atomic",
                       "INV-05", NonAtomicIdempotency::new),
            // A key that is ignored fails the sequential replay and the concurrent one alike;
            // both invariants exist because a ledger can hold one and not the other.
            new Mutant("M-02", "idempotency key is ignored entirely",
                       Set.of("INV-04", "INV-05"), NoIdempotency::new),
            new Mutant("M-03", "hot-account balance is read-modify-written without the lock",
                       "INV-06", UnlockedHotAccount::new),
            new Mutant("M-04", "sufficient-funds check runs before the lock is taken",
                       "INV-09", RacyOverdraftGuard::new),
            new Mutant("M-05", "amounts round-trip through double",
                       "INV-10", FloatingPointMoney::new),
            new Mutant("M-06", "journal append skipped on one leg while the balance is written",
                       Set.of("INV-08", "INV-13"), DriftingProjection::new),
            // Reordering the journal moves the original entries out from under INV-12's
            // prefix check as well. The coupling is honest — the entries genuinely are not
            // where they were — though INV-02 is the one that names the cause.
            new Mutant("M-07", "journal view is re-sorted by account instead of commit order",
                       Set.of("INV-02", "INV-12"), AccountOrderedJournal::new),

            // ---- added to close the coverage gap: seven invariants had no mutant at all ----

            new Mutant("M-08", "an unbalanced transaction is plugged to a suspense account "
                       + "instead of being refused",
                       "INV-01", SuspensePlug::new),
            new Mutant("M-09", "each entry is hashed on its own instead of being chained to the last",
                       "INV-03", UnchainedHashes::new),
            new Mutant("M-10", "the internal posting path books the payer's leg to a suspense "
                       + "account, so a transfer creates money",
                       "INV-07", LeakyInternalTransfer::new),
            new Mutant("M-11", "legs are netted across currencies rather than per currency",
                       "INV-11", CurrencyBlindNetting::new),
            new Mutant("M-12", "a reversal deletes the original entries instead of compensating them",
                       "INV-12", DeletingReversal::new),
            // INV-08 and INV-13 assert the same identity — the read path agrees with the journal
            // projection — so a defect in replay cannot trip one without the other. Declared
            // rather than contrived around; see the class javadoc.
            new Mutant("M-13", "replay drops the tail of the journal, as a stale snapshot would",
                       Set.of("INV-08", "INV-13"), TruncatedReplay::new),
            new Mutant("M-14", "per-account sequence numbers are stamped from a shared counter",
                       "INV-14", CollidingAccountSequence::new)
        );
    }

    // ---------------------------------------------------------------- mutants

    /** M-01: the classic. A check, then a write, with a window in between. */
    static final class NonAtomicIdempotency extends ReferenceLedgerDelegate {
        private final Set<String> seen = ConcurrentHashMap.newKeySet();
        @Override public PostResult post(Transaction t) {
            if (seen.contains(t.idempotencyKey()))          // read ...
                return PostResult.duplicate(t.transactionId());
            Thread.yield();                                  // ... window ...
            seen.add(t.idempotencyKey());                    // ... write
            return delegate.post(strip(t));
        }
    }

    /** M-02: no deduplication at all. Every submission is a fresh transaction. */
    static final class NoIdempotency extends ReferenceLedgerDelegate {
        @Override public PostResult post(Transaction t) { return delegate.post(strip(t)); }
    }

    /** M-03: hot accounts accumulate through an unsynchronised long. */
    static final class UnlockedHotAccount extends ReferenceLedgerDelegate {
        private final Map<String, long[]> unsafe = new HashMap<>();
        @Override public PostResult post(Transaction t) {
            PostResult r = delegate.post(t);
            if (r.status() == PostStatus.APPLIED)
                for (Leg l : t.legs())
                    if (l.accountId().startsWith("acct:hot")) {
                        long[] cell = unsafe.computeIfAbsent(l.accountId(), k -> new long[1]);
                        long cur = cell[0];
                        Thread.yield();
                        cell[0] = cur + l.signed();
                    }
            return r;
        }
        @Override public long balance(String a, String c) {
            long[] cell = unsafe.get(a);
            return cell != null ? cell[0] : delegate.balance(a, c);
        }
    }

    /** M-04: the guard is evaluated outside the critical section. */
    static final class RacyOverdraftGuard extends ReferenceLedgerDelegate {
        @Override public PostResult post(Transaction t) {
            for (Leg l : t.legs())
                if (l.type() == EntryType.DEBIT && !t.allowOverdraft()
                        && delegate.balance(l.accountId(), l.currency()) < l.amountSubunits())
                    return PostResult.rejected(t.transactionId(), "insufficient funds");
            Thread.yield();
            return delegate.post(new Transaction(t.idempotencyKey(), t.legs(),
                    t.transactionId(), true, t.metadata()));   // guard already "done"
        }
    }

    /**
     * M-05: balances accumulate in double, in major units, exactly as a system that took its
     * money type from a JSON schema would.
     *
     * <p>The previous version multiplied every balance by 0.99999999, which is not a double
     * defect but an arbitrary scaling error: it corrupted every read and so tripped five
     * invariants, proving only that the suite notices wrong balances. This version is honest.
     * Amounts that are exact in binary — 1.00, 0.50, 3.00 — survive it untouched, so it is
     * invisible everywhere except the one invariant that moves a thousand single subunits,
     * because 0.01 has no exact binary representation. That is what INV-10 is for.
     */
    static final class FloatingPointMoney extends ReferenceLedgerDelegate {
        private final Map<String, Double> dollars = new ConcurrentHashMap<>();

        @Override public void reset() { dollars.clear(); delegate.reset(); }

        @Override public PostResult post(Transaction t) {
            PostResult r = delegate.post(t);
            if (r.status() == PostStatus.APPLIED)
                for (Leg l : t.legs())
                    dollars.merge(l.accountId() + "|" + l.currency(), l.signed() / 100.0, Double::sum);
            return r;
        }

        @Override public long balance(String a, String c) {
            Double d = dollars.get(a + "|" + c);
            return d == null ? delegate.balance(a, c) : (long) (d * 100);
        }
    }

    /**
     * M-06: the balance is written but the journal append for one leg is lost, every so often —
     * a failed write on the second statement of a routine that only checks the first.
     *
     * <p>Approaches the same divergence as M-13 from the other side: this one corrupts the
     * journal and leaves the read path correct, M-13 corrupts the replay and leaves the journal
     * correct. That the pair INV-08 and INV-13 catches both directions is the point of having
     * both mutants; that it is the same pair each time is a real property of those two
     * invariants, which assert the same identity at different scales.
     */
    static final class DriftingProjection extends ReferenceLedgerDelegate {
        private final AtomicInteger posts = new AtomicInteger();
        private final Set<String> lostALeg = ConcurrentHashMap.newKeySet();

        @Override public void reset() { posts.set(0); lostALeg.clear(); delegate.reset(); }

        @Override public PostResult post(Transaction t) {
            PostResult r = delegate.post(t);
            if (r.status() == PostStatus.APPLIED && posts.incrementAndGet() % 50 == 0)
                lostALeg.add(t.transactionId());
            return r;
        }

        @Override public List<JournalEntry> journal() {
            List<JournalEntry> out = new ArrayList<>();
            for (JournalEntry e : delegate.journal())
                if (!(e.type() == EntryType.DEBIT && lostALeg.contains(e.transactionId())))
                    out.add(e);
            return out;
        }
    }

    /**
     * M-07: the journal is materialized with the wrong ORDER BY — by account, not by
     * commit order — so as new rows land they slot in beside earlier ones and the
     * append-only prefix shifts underneath a reader. The physical rows are intact and
     * every balance still ties out; only the order is a lie. Crucially this ledger
     * declares no hash chain, so INV-03 is skipped and INV-02 is the one thing left to
     * catch it — which is the whole reason INV-02 is a separate BLOCKER.
     */
    static final class AccountOrderedJournal extends ReferenceLedgerDelegate {
        @Override public Set<Capability> capabilities() {
            EnumSet<Capability> c = EnumSet.copyOf(delegate.capabilities());
            c.remove(Capability.HASH_CHAIN);          // no chain to lean on
            return c;
        }
        @Override public PostResult post(Transaction t) { return delegate.post(t); }
        @Override public List<JournalEntry> journal() {
            List<JournalEntry> view = new ArrayList<>(delegate.journal());
            view.sort(Comparator.comparing(JournalEntry::accountId));   // stable: keeps per-account order
            return view;
        }
    }

    /** M-08: the plug account. Books out of balance rather than refusing to book. */
    static final class SuspensePlug extends ReferenceLedgerDelegate {
        @Override public PostResult post(Transaction t) {
            long net = t.legs().stream().mapToLong(Leg::signed).sum();
            if (net == 0) return delegate.post(t);
            List<Leg> plugged = new ArrayList<>(t.legs());
            plugged.add(net > 0 ? new Leg("acct:suspense", EntryType.DEBIT, net, t.legs().get(0).currency())
                                : new Leg("acct:suspense", EntryType.CREDIT, -net, t.legs().get(0).currency()));
            return delegate.post(new Transaction(t.idempotencyKey(), plugged,
                    t.transactionId(), true, t.metadata()));
        }
    }

    /** M-09: every entry verifies on its own, and the chain proves nothing. */
    static final class UnchainedHashes extends ReferenceLedgerDelegate {
        @Override public PostResult post(Transaction t) { return delegate.post(t); }
        @Override public List<JournalEntry> journal() {
            List<JournalEntry> out = new ArrayList<>();
            for (JournalEntry e : delegate.journal())
                out.add(new JournalEntry(e.entryId(), e.transactionId(), e.accountId(), e.type(),
                        e.amountSubunits(), e.currency(), e.sequence(), e.accountSequence(),
                        "GENESIS", Model.hashEntry("GENESIS", e.transactionId(), e.accountId(),
                                e.type().name(), e.amountSubunits(), e.currency(), e.sequence())));
            return out;
        }
    }

    /**
     * M-10: internal movements — the ones exempted from the overdraft guard — go through a
     * separate, less-travelled posting routine that books the debit to a suspense account. Both
     * the journal and the balances agree with each other; they just do not agree with reality,
     * so every consistency check passes and only conservation notices.
     */
    static final class LeakyInternalTransfer extends ReferenceLedgerDelegate {
        @Override public PostResult post(Transaction t) {
            if (!t.allowOverdraft() || t.legs().size() != 2) return delegate.post(t);
            List<Leg> rerouted = new ArrayList<>();
            for (Leg l : t.legs())
                rerouted.add(l.type() == EntryType.DEBIT && l.accountId().startsWith("acct:")
                        ? new Leg("external:suspense", l.type(), l.amountSubunits(), l.currency())
                        : l);
            return delegate.post(new Transaction(t.idempotencyKey(), rerouted,
                    t.transactionId(), true, t.metadata()));
        }
    }

    /** M-11: a USD debit closes an EUR credit because the sum is taken over both. */
    static final class CurrencyBlindNetting extends ReferenceLedgerDelegate {
        @Override public PostResult post(Transaction t) {
            String first = t.legs().get(0).currency();
            if (t.legs().stream().allMatch(l -> l.currency().equals(first))) return delegate.post(t);
            List<Leg> flattened = new ArrayList<>();
            for (Leg l : t.legs())
                flattened.add(new Leg(l.accountId(), l.type(), l.amountSubunits(), first));
            return delegate.post(new Transaction(t.idempotencyKey(), flattened,
                    t.transactionId(), true, t.metadata()));
        }
    }

    /** M-12: the reversal tidies up after itself, and the auditor sees only the tidy version. */
    static final class DeletingReversal extends ReferenceLedgerDelegate {
        private static final String PREFIX = "comp-of-";
        // Journal entries carry the transaction id, not the idempotency key, so the reversal has
        // to look up what it is reversing before it can erase it.
        private final Map<String, String> txnByKey = new ConcurrentHashMap<>();
        private final Set<String> erased = ConcurrentHashMap.newKeySet();

        @Override public void reset() { txnByKey.clear(); erased.clear(); delegate.reset(); }

        @Override public PostResult post(Transaction t) {
            PostResult r = delegate.post(t);
            if (r.status() != PostStatus.APPLIED) return r;
            txnByKey.put(t.idempotencyKey(), t.transactionId());
            if (t.idempotencyKey().startsWith(PREFIX)) {
                String original = txnByKey.get(t.idempotencyKey().substring(PREFIX.length()));
                if (original != null) erased.add(original);
            }
            return r;
        }

        @Override public List<JournalEntry> journal() {
            List<JournalEntry> out = new ArrayList<>();
            for (JournalEntry e : delegate.journal())
                if (!erased.contains(e.transactionId())) out.add(e);
            return out;
        }
    }

    /** M-13: rebuilt from a snapshot that is one page short of the truth. */
    static final class TruncatedReplay extends ReferenceLedgerDelegate {
        @Override public PostResult post(Transaction t) { return delegate.post(t); }
        @Override public long replayBalance(String acct, String ccy) {
            List<JournalEntry> j = delegate.journal();
            return j.stream().limit(Math.max(0, j.size() - 4))
                    .filter(e -> e.accountId().equals(acct) && e.currency().equals(ccy))
                    .mapToLong(JournalEntry::signed).sum();
        }
    }

    /** M-14: one counter for every account, so "the third entry on this account" is a lie. */
    static final class CollidingAccountSequence extends ReferenceLedgerDelegate {
        @Override public PostResult post(Transaction t) { return delegate.post(t); }
        @Override public List<JournalEntry> journal() {
            List<JournalEntry> out = new ArrayList<>();
            for (JournalEntry e : delegate.journal())
                out.add(new JournalEntry(e.entryId(), e.transactionId(), e.accountId(), e.type(),
                        e.amountSubunits(), e.currency(), e.sequence(), 1L,
                        e.prevHash(), e.entryHash()));
            return out;
        }
    }

    // ---------------------------------------------------------------- plumbing

    static Transaction strip(Transaction t) {
        return new Transaction(UUID.randomUUID().toString(), t.legs(),
                t.transactionId(), t.allowOverdraft(), t.metadata());
    }

    abstract static class ReferenceLedgerDelegate implements LedgerAdapter {
        protected final ReferenceLedger delegate = new ReferenceLedger();
        @Override public String name() { return "mutant:" + getClass().getSimpleName(); }
        @Override public Set<Capability> capabilities() { return delegate.capabilities(); }
        @Override public void reset() { delegate.reset(); }
        @Override public long balance(String a, String c) { return delegate.balance(a, c); }
        @Override public List<JournalEntry> journal() { return delegate.journal(); }
    }

    private Mutants() {}
}
