package com.technomorph.lck.tck;

import com.technomorph.lck.spi.LedgerAdapter;
import com.technomorph.lck.spi.Capability;
import com.technomorph.lck.spi.Model.*;

import java.util.*;

/**
 * Adapter Technology Compatibility Kit.
 *
 * <p>This is the answer to "how do I know the client wired it up correctly?"
 *
 * <p>The Java interface guarantees the <em>signature</em> at compile time — a client
 * cannot pass an object with the wrong method shapes. What it cannot guarantee is the
 * <em>semantics</em>: that {@code balance()} returns minor units and not dollars, that
 * {@code reset()} actually empties state, that {@code journal()} comes back in commit
 * order. Every one of those mistakes produces a plausible-looking red scorecard that is
 * entirely the adapter's fault.
 *
 * <p>So the TCK tests the adapter, not the ledger. It uses only behaviour every correct
 * adapter must exhibit regardless of how the underlying ledger is built. Run it first.
 * If the TCK fails, the conformance results are meaningless and the runner refuses to
 * report them.
 *
 * <p>Same pattern as the JDBC and Jakarta Persistence TCKs, and for the same reason:
 * a pluggable contract is only worth something if plugins can be verified.
 */
public final class AdapterTck {

    public record Finding(String id, String requirement, boolean ok, String detail) {}

    public static List<Finding> verify(LedgerAdapter led) {
        List<Finding> out = new ArrayList<>();
        out.add(check("TCK-01", "reset() empties all state", () -> {
            led.seed("tck:a", 5_000);
            if (led.journal().isEmpty()) return "journal empty after a successful post";
            led.reset();
            if (!led.journal().isEmpty()) return "journal not empty after reset()";
            if (led.balance("tck:a") != 0) return "balance " + led.balance("tck:a") + " after reset(), expected 0";
            return null;
        }));

        out.add(check("TCK-02", "balance() is in minor units, not major", () -> {
            led.reset();
            led.seed("tck:a", 12_345);          // 123.45
            long b = led.balance("tck:a");
            if (b == 123) return "balance() returned 123 for a 12345-subunit credit — "
                    + "the adapter is dividing by 100 or the ledger stores major units";
            if (b != 12_345) return "balance() returned " + b + ", expected 12345";
            return null;
        }));

        out.add(check("TCK-03", "post() is honest about what it did", () -> {
            led.reset();
            led.seed("tck:a", 5_000);
            PostResult r = led.post(Transaction.transfer("tck:a", "tck:b", 1_000, "tck-3"));
            if (r == null || r.status() == null) return "post() returned null status";
            if (r.status() != PostStatus.APPLIED)
                return "a valid, funded transfer returned " + r.status() + " (" + r.reason() + ")";
            if (led.balance("tck:b") != 1_000)
                return "post() reported APPLIED but destination balance is " + led.balance("tck:b");
            return null;
        }));

        out.add(check("TCK-04", "rejection is a return value, not an exception", () -> {
            led.reset();
            led.seed("tck:a", 100);
            try {
                PostResult r = led.post(Transaction.transfer("tck:a", "tck:b", 900_000, "tck-4"));
                if (r.status() == PostStatus.APPLIED && !led.supports(Capability.OVERDRAFT_GUARD))
                    return null;   // no guard declared; overdraft is legitimate here
                if (r.status() != PostStatus.REJECTED)
                    return "an unfunded transfer returned " + r.status() + " with OVERDRAFT_GUARD declared";
                return null;
            } catch (Exception e) {
                return "post() threw " + e.getClass().getSimpleName()
                        + " for a business rejection; the suite cannot distinguish that from a crash";
            }
        }));

        out.add(check("TCK-05", "journal() is in commit order", () -> {
            led.reset();
            led.seed("tck:a", 50_000);
            for (int i = 0; i < 10; i++)
                led.post(Transaction.transfer("tck:a", "tck:b", 100, "tck5-" + i));
            List<JournalEntry> j = led.journal();
            long prev = Long.MIN_VALUE;
            for (JournalEntry e : j) {
                if (e.sequence() <= prev)
                    return "sequence went " + prev + " -> " + e.sequence()
                            + "; journal() must return commit order, not insertion order of a HashMap";
                prev = e.sequence();
            }
            return j.size() >= 20 ? null : "expected >= 20 entries, saw " + j.size();
        }));

        out.add(check("TCK-06", "journal() reflects both legs of every transaction", () -> {
            led.reset();
            led.seed("tck:a", 5_000);
            int before = led.journal().size();
            led.post(Transaction.transfer("tck:a", "tck:b", 700, "tck-6"));
            int delta = led.journal().size() - before;
            return delta == 2 ? null : "a two-leg transfer added " + delta
                    + " journal entries; the adapter is likely filtering by account or dropping a leg";
        }));

        out.add(check("TCK-07", "journal() is a snapshot, not a live view", () -> {
            led.reset();
            led.seed("tck:a", 5_000);
            List<JournalEntry> snap = led.journal();
            int n = snap.size();
            led.post(Transaction.transfer("tck:a", "tck:b", 100, "tck-7"));
            return snap.size() == n ? null
                    : "the list returned by journal() mutated underneath us; return a copy";
        }));

        out.add(check("TCK-08", "declared capabilities match observable behaviour", () -> {
            led.reset();
            if (led.supports(Capability.HASH_CHAIN)) {
                led.seed("tck:a", 1_000);
                JournalEntry e = led.journal().get(0);
                if (e.entryHash() == null || e.entryHash().isBlank())
                    return "HASH_CHAIN declared but entryHash is empty";
            }
            if (led.supports(Capability.REPLAY)) {
                led.reset();
                led.seed("tck:a", 3_300);
                if (led.replayBalance("tck:a", "USD") != led.balance("tck:a"))
                    return "REPLAY declared but replayBalance disagrees with balance on a trivial case";
            }
            return null;
        }));

        out.add(check("TCK-09", "the adapter is talking to a scratch environment", () -> {
            led.reset();
            if (!led.journal().isEmpty())
                return "journal is non-empty immediately after reset() — this adapter may be "
                        + "pointed at an environment with existing data. Never run against production.";
            return null;
        }));

        return out;
    }

    public static boolean trustworthy(List<Finding> findings) {
        return findings.stream().allMatch(Finding::ok);
    }

    // ------------------------------------------------------------------

    private interface Probe { String run() throws Exception; }   // null == pass

    private static Finding check(String id, String requirement, Probe p) {
        try {
            String problem = p.run();
            return new Finding(id, requirement, problem == null, problem == null ? "ok" : problem);
        } catch (Throwable t) {
            return new Finding(id, requirement, false,
                    t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private AdapterTck() {}
}
