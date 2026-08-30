package com.technomorph.lck.core;

import com.technomorph.lck.examples.ReferenceLedger;
import com.technomorph.lck.spi.Capability;
import com.technomorph.lck.spi.LedgerAdapter;
import com.technomorph.lck.spi.Model.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p>Two gaps, both open and both worth closing before this corpus is cited as evidence:
 * <ul>
 *   <li>Seven invariants have a mutant. INV-01, 03, 07, 11, 12, 13 and 14 do not, so they
 *       are not yet demonstrated to catch anything.</li>
 *   <li>{@code MutationTest} asserts only that the named invariant broke, not that the others
 *       held. Until it does, a mutant with a wide blast radius passes, and specificity — what
 *       makes a finding attributable to one defect rather than to general malaise — is not
 *       enforced.</li>
 * </ul>
 *
 * <p>The other half of the vetting is {@code noFalsePositives}: every invariant must pass the
 * reference ledger. An invariant that fails a known-good implementation is a bug in the
 * invariant, not a finding, and it is caught here rather than in front of a client.
 */
public final class Mutants {

    /** Which invariant each mutant is expected to trip, and nothing else. */
    public record Mutant(String id, String defect, String expectedInvariant,
                         java.util.function.Supplier<LedgerAdapter> factory) {}

    public static List<Mutant> all() {
        return List.of(
            new Mutant("M-01", "idempotency claim is check-then-act instead of atomic",
                       "INV-05", NonAtomicIdempotency::new),
            new Mutant("M-02", "idempotency key is ignored entirely",
                       "INV-04", NoIdempotency::new),
            new Mutant("M-03", "hot-account balance is read-modify-written without the lock",
                       "INV-06", UnlockedHotAccount::new),
            new Mutant("M-04", "sufficient-funds check runs before the lock is taken",
                       "INV-09", RacyOverdraftGuard::new),
            new Mutant("M-05", "amounts round-trip through double",
                       "INV-10", FloatingPointMoney::new),
            new Mutant("M-06", "balance cache updated but journal append skipped on one leg",
                       "INV-08", DriftingProjection::new),
            new Mutant("M-07", "journal view is re-sorted by account instead of commit order",
                       "INV-02", AccountOrderedJournal::new)
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

    /** M-05: money survives a trip through double. Fine until it isn't. */
    static final class FloatingPointMoney extends ReferenceLedgerDelegate {
        @Override public PostResult post(Transaction t) { return delegate.post(t); }
        @Override public long balance(String a, String c) {
            double dollars = delegate.balance(a, c) / 100.0;
            return (long) (dollars * 100.0 * 0.99999999);   // the drift is the point
        }
    }

    /** M-06: the cached balance and the journal are updated by different code paths. */
    static final class DriftingProjection extends ReferenceLedgerDelegate {
        private final Map<String, Long> cache = new ConcurrentHashMap<>();
        @Override public PostResult post(Transaction t) {
            PostResult r = delegate.post(t);
            if (r.status() == PostStatus.APPLIED)
                for (Leg l : t.legs())
                    if (l.type() != EntryType.DEBIT)          // credits cached, debits forgotten
                        cache.merge(l.accountId(), l.signed(), Long::sum);
            return r;
        }
        @Override public long balance(String a, String c) { return cache.getOrDefault(a, 0L); }
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
