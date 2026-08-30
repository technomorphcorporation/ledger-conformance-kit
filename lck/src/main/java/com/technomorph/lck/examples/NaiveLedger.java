package com.technomorph.lck.examples;

import com.technomorph.lck.spi.LedgerAdapter;
import com.technomorph.lck.spi.Capability;
import com.technomorph.lck.spi.Model.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** The control group. Every choice here is one I have found in a real production system. */
public final class NaiveLedger implements LedgerAdapter {

    private final Map<String, Double> balances = new ConcurrentHashMap<>();   // double. yes, really.
    private final List<JournalEntry> journal = Collections.synchronizedList(new ArrayList<>());

    @Override public String name() { return "naive-ledger (jvm)"; }

    @Override public Set<Capability> capabilities() {
        return EnumSet.of(Capability.OVERDRAFT_GUARD, Capability.COMPENSATION, Capability.REPLAY);
    }

    @Override public void reset() { balances.clear(); journal.clear(); }

    @Override public PostResult post(Transaction txn) {
        if (txn.legs().stream().mapToLong(Leg::signed).sum() != 0)
            return PostResult.rejected(txn.transactionId(), "unbalanced");

        if (!txn.allowOverdraft())                       // read now ...
            for (Leg l : txn.legs())
                if (l.type() == EntryType.DEBIT
                        && balances.getOrDefault(key(l), 0.0) < l.amountSubunits() / 100.0)
                    return PostResult.rejected(txn.transactionId(), "insufficient funds");

        Thread.yield();                                  // ... latency ...

        for (Leg l : txn.legs()) {
            double cur = balances.getOrDefault(key(l), 0.0);
            Thread.yield();
            balances.put(key(l), cur + l.signed() / 100.0);   // ... write. the lost update lives here.

            long seq = journal.size() + 1;
            journal.add(new JournalEntry(UUID.randomUUID().toString(), txn.transactionId(),
                    l.accountId(), l.type(), l.amountSubunits(), l.currency(), seq, seq, "", ""));
        }
        return PostResult.applied(txn.transactionId());
    }

    private static String key(Leg l) { return l.accountId() + "|" + l.currency(); }

    @Override public long balance(String acct, String ccy) {
        return (long) (balances.getOrDefault(acct + "|" + ccy, 0.0) * 100);
    }

    @Override public List<JournalEntry> journal() { return List.copyOf(journal); }
}
