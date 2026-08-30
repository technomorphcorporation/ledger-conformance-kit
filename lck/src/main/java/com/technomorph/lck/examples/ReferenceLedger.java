package com.technomorph.lck.examples;

import com.technomorph.lck.spi.LedgerAdapter;
import com.technomorph.lck.spi.Capability;
import com.technomorph.lck.spi.Model;
import com.technomorph.lck.spi.Model.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;

/** Holds every invariant. Annotated with what each structure maps to in production. */
public final class ReferenceLedger implements LedgerAdapter {

    private static final int HOT_SHARDS = 16;

    private final List<JournalEntry> journal = new ArrayList<>();
    private final Object journalLock = new Object();
    private final Map<String, long[]> shards = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock[]> shardLocks = new ConcurrentHashMap<>();
    private final Map<String, Claim> idem = new ConcurrentHashMap<>();   // -> Redis SETNX + UNIQUE index
    private final Map<String, Long> acctSeq = new ConcurrentHashMap<>();
    private long seq = 0;
    private String lastHash = "GENESIS";

    @Override public String name() { return "reference-ledger (jvm)"; }

    @Override public Set<Capability> capabilities() {
        return EnumSet.of(Capability.HASH_CHAIN, Capability.OVERDRAFT_GUARD,
                          Capability.COMPENSATION, Capability.REPLAY);
    }

    @Override public void reset() {
        synchronized (journalLock) { journal.clear(); seq = 0; lastHash = "GENESIS"; }
        shards.clear(); shardLocks.clear(); idem.clear(); acctSeq.clear();
    }

    /** Accrual-only accounts fan out. Guarded accounts keep one serialization point. */
    private static int shardCount(String acct) {
        return (acct.startsWith("external:") || acct.startsWith("acct:hot")) ? HOT_SHARDS : 1;
    }

    private long[] slots(String acct, String ccy) {
        String k = acct + "|" + ccy;
        long[] s = shards.get(k);
        if (s != null) return s;
        synchronized (shards) {
            return shards.computeIfAbsent(k, key -> {
                int n = shardCount(acct);
                ReentrantLock[] ls = new ReentrantLock[n];
                for (int i = 0; i < n; i++) ls[i] = new ReentrantLock();
                shardLocks.put(key, ls);
                return new long[n];
            });
        }
    }

    private int pickShard(String acct, String ccy, String salt) {
        int n = slots(acct, ccy).length;
        return n == 1 ? 0 : Math.floorMod(salt.hashCode(), n);
    }

    // ------------------------------------------------------------------ idempotency

    /**
     * A claim on an idempotency key, and the outcome it eventually settles to.
     *
     * <p>The obvious implementation — put the key, remove it again if the transaction turns out
     * to be rejected — has a hole that this ledger existed to demonstrate the absence of. A
     * concurrent submission of the same key sees the claim and is told DUPLICATE, and only
     * afterwards is the claim withdrawn because the first attempt was refused. The caller has
     * now been told its payment was already applied when nothing was posted at all: a silently
     * dropped transfer, which is precisely the defect the kit sells itself on finding.
     *
     * <p>So a claim is not a marker, it is a promise to settle. A second caller waits for the
     * outcome and only answers DUPLICATE if something was actually applied; otherwise the key
     * is released and the second caller takes its own turn.
     */
    private static final class Claim {
        private final CountDownLatch settled = new CountDownLatch(1);
        private volatile PostResult outcome;      // null until settled, and null if it threw
    }

    @Override public PostResult post(Transaction txn) {
        if (txn.legs().isEmpty()) return PostResult.rejected(txn.transactionId(), "no legs");
        if (txn.netByCurrency().values().stream().anyMatch(v -> v != 0L))
            return PostResult.rejected(txn.transactionId(), "unbalanced per currency");

        String key = txn.idempotencyKey();
        while (true) {
            Claim mine = new Claim();
            Claim holder = idem.putIfAbsent(key, mine);   // the in-memory ON CONFLICT DO NOTHING

            if (holder == null) {
                PostResult result = null;
                try {
                    return result = commit(txn);
                } finally {
                    mine.outcome = result;                // set before releasing the waiters
                    mine.settled.countDown();
                    if (result == null || result.status() != PostStatus.APPLIED)
                        idem.remove(key, mine);           // nothing was posted, so nothing is claimed
                }
            }

            PostResult settled = awaitOutcome(holder);
            if (settled != null && settled.status() == PostStatus.APPLIED)
                return PostResult.duplicate(settled.transactionId());

            // The holder posted nothing, so this key never took effect. Free it and take a turn.
            idem.remove(key, holder);
        }
    }

    private static PostResult awaitOutcome(Claim holder) {
        try {
            holder.settled.await();
            return holder.outcome;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for an idempotency claim "
                    + "to settle", e);
        }
    }

    // ------------------------------------------------------------------ commit

    private PostResult commit(Transaction txn) {
        // A total order over lock names, so concurrent multi-leg transactions cannot deadlock.
        // A guarded transaction takes every shard of the accounts it touches, because the
        // sufficient-funds check below reads every shard: holding one while reading sixteen
        // would be a guard with a window in it.
        TreeMap<String, ReentrantLock> ordered = new TreeMap<>();
        for (Leg l : txn.legs()) {
            String account = l.accountId() + "|" + l.currency();
            ReentrantLock[] locks = shardLocksFor(l.accountId(), l.currency());
            if (txn.allowOverdraft()) {
                int s = pickShard(l.accountId(), l.currency(), txn.transactionId());
                ordered.put(account + "|" + s, locks[s]);
            } else {
                for (int s = 0; s < locks.length; s++) ordered.put(account + "|" + s, locks[s]);
            }
        }

        List<ReentrantLock> held = new ArrayList<>(ordered.values());
        held.forEach(ReentrantLock::lock);
        try {
            if (!txn.allowOverdraft()) {
                Map<String, Long> delta = new HashMap<>();
                for (Leg l : txn.legs())
                    delta.merge(l.accountId() + "|" + l.currency(), l.signed(), Long::sum);
                for (var e : delta.entrySet()) {
                    if (e.getValue() < 0) {
                        int split = e.getKey().lastIndexOf('|');
                        String acct = e.getKey().substring(0, split), ccy = e.getKey().substring(split + 1);
                        if (Arrays.stream(slots(acct, ccy)).sum() + e.getValue() < 0)
                            return PostResult.rejected(txn.transactionId(), "insufficient funds on " + acct);
                    }
                }
            }

            // The journal append and the balance it implies happen in one critical section, so a
            // concurrent reader can never see an entry whose effect on the balance has not
            // landed yet. INV-08 asserts exactly this, and a reference implementation that only
            // satisfied it at quiescence would be assuming the thing it is meant to demonstrate.
            synchronized (journalLock) {
                for (Leg l : txn.legs()) {
                    seq++;
                    long as = acctSeq.merge(l.accountId(), 1L, Long::sum);
                    String h = Model.hashEntry(lastHash, txn.transactionId(), l.accountId(),
                            l.type().name(), l.amountSubunits(), l.currency(), seq);
                    journal.add(new JournalEntry(UUID.randomUUID().toString(), txn.transactionId(),
                            l.accountId(), l.type(), l.amountSubunits(), l.currency(),
                            seq, as, lastHash, h));
                    lastHash = h;
                }
                for (Leg l : txn.legs())
                    slots(l.accountId(), l.currency())
                            [pickShard(l.accountId(), l.currency(), txn.transactionId())] += l.signed();
            }
        } finally {
            for (int i = held.size() - 1; i >= 0; i--) held.get(i).unlock();
        }
        return PostResult.applied(txn.transactionId());
    }

    private ReentrantLock[] shardLocksFor(String acct, String ccy) {
        slots(acct, ccy);                       // ensures both maps are populated
        return shardLocks.get(acct + "|" + ccy);
    }

    // ------------------------------------------------------------------ reads

    @Override public long balance(String acct, String ccy) {
        // Under the same lock as the journal, so balance() and journal() agree at any instant
        // rather than only once writers have drained.
        synchronized (journalLock) { return Arrays.stream(slots(acct, ccy)).sum(); }
    }

    @Override public List<JournalEntry> journal() {
        synchronized (journalLock) { return List.copyOf(journal); }
    }
}
