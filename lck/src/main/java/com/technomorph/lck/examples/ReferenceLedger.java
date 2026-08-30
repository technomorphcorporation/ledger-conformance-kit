package com.technomorph.lck.examples;

import com.technomorph.lck.spi.LedgerAdapter;
import com.technomorph.lck.spi.Capability;
import com.technomorph.lck.spi.Model;
import com.technomorph.lck.spi.Model.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Holds every invariant. Annotated with what each structure maps to in production. */
public final class ReferenceLedger implements LedgerAdapter {

    private static final int HOT_SHARDS = 16;

    private final List<JournalEntry> journal = new ArrayList<>();
    private final Object journalLock = new Object();
    private final Map<String, long[]> shards = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock[]> shardLocks = new ConcurrentHashMap<>();
    private final Map<String, String> idem = new ConcurrentHashMap<>();   // -> Redis SETNX + UNIQUE index
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

    @Override public PostResult post(Transaction txn) {
        if (txn.legs().isEmpty()) return PostResult.rejected(txn.transactionId(), "no legs");
        if (txn.netByCurrency().values().stream().anyMatch(v -> v != 0L))
            return PostResult.rejected(txn.transactionId(), "unbalanced per currency");

        // The claim. putIfAbsent is the in-memory stand-in for ON CONFLICT DO NOTHING.
        String prior = idem.putIfAbsent(txn.idempotencyKey(), txn.transactionId());
        if (prior != null) return PostResult.duplicate(prior);

        try {
            TreeMap<String, ReentrantLock> ordered = new TreeMap<>();   // total lock order
            for (Leg l : txn.legs()) {
                slots(l.accountId(), l.currency());
                int s = pickShard(l.accountId(), l.currency(), txn.transactionId());
                ordered.put(l.accountId() + "|" + l.currency() + "|" + s,
                            shardLocks.get(l.accountId() + "|" + l.currency())[s]);
            }
            List<ReentrantLock> locks = new ArrayList<>(ordered.values());
            locks.forEach(ReentrantLock::lock);
            try {
                if (!txn.allowOverdraft()) {
                    Map<String, Long> delta = new HashMap<>();
                    for (Leg l : txn.legs())
                        delta.merge(l.accountId() + "|" + l.currency(), l.signed(), Long::sum);
                    for (var e : delta.entrySet()) {
                        if (e.getValue() < 0) {
                            String[] p = e.getKey().split("\\|");
                            if (Arrays.stream(slots(p[0], p[1])).sum() + e.getValue() < 0) {
                                idem.remove(txn.idempotencyKey());
                                return PostResult.rejected(txn.transactionId(), "insufficient funds on " + p[0]);
                            }
                        }
                    }
                }
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
                }
                for (Leg l : txn.legs())
                    slots(l.accountId(), l.currency())[pickShard(l.accountId(), l.currency(), txn.transactionId())]
                            += l.signed();
            } finally {
                for (int i = locks.size() - 1; i >= 0; i--) locks.get(i).unlock();
            }
        } catch (RuntimeException e) {
            idem.remove(txn.idempotencyKey());
            throw e;
        }
        return PostResult.applied(txn.transactionId());
    }

    @Override public long balance(String acct, String ccy) { return Arrays.stream(slots(acct, ccy)).sum(); }

    @Override public List<JournalEntry> journal() {
        synchronized (journalLock) { return List.copyOf(journal); }
    }
}
