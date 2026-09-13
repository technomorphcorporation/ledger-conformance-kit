package com.technomorph.lck.adapter;

import com.technomorph.lck.adapter.FormanceLedgerAdapter.Posting;
import com.technomorph.lck.spi.Model.Leg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The kit models a transaction as independent debit and credit legs; Formance models it as
 * directed source-to-destination postings. Converting between them is the only place this adapter
 * makes a choice rather than a translation, which makes it the only place a maintainer can
 * reasonably say the harness, not the ledger, produced the finding.
 *
 * <p>So the property that matters is not which pairing comes out. It is that <b>every pairing
 * moves exactly the money the legs described, to and from exactly the accounts they named</b>.
 * If that holds, the choice cannot change what any invariant asserts on, and the objection has
 * nowhere to land.
 */
class FormancePostingsTest {

    @Test
    @DisplayName("the ordinary two-leg transfer maps to exactly one posting")
    void twoLegsAreOnePosting() {
        List<Posting> ps = FormanceLedgerAdapter.postings(List.of(
                Leg.debit("acct:a", 50_000), Leg.credit("acct:b", 50_000)));

        assertEquals(1, ps.size(), () -> "expected a single posting, got " + ps);
        assertEquals("acct:a", ps.get(0).source(), "the debited account is the source");
        assertEquals("acct:b", ps.get(0).destination(), "the credited account is the destination");
        assertEquals(50_000, ps.get(0).amount());
    }

    @Test
    @DisplayName("two debits funding one credit split across postings without losing a subunit")
    void manyToOneSplits() {
        List<Posting> ps = FormanceLedgerAdapter.postings(List.of(
                Leg.debit("acct:a", 30_000), Leg.debit("acct:b", 20_000),
                Leg.credit("acct:c", 50_000)));

        assertEquals(50_000, ps.stream().mapToLong(Posting::amount).sum(),
                () -> "the postings must move the full 500.00: " + ps);
        assertTrue(ps.stream().allMatch(p -> p.destination().equals("acct:c")), ps::toString);
    }

    @Test
    @DisplayName("currencies never cross: a USD leg is never paired against a EUR one")
    void currenciesDoNotCross() {
        List<Posting> ps = FormanceLedgerAdapter.postings(List.of(
                new Leg("acct:a", com.technomorph.lck.spi.Model.EntryType.DEBIT, 10_000, "USD"),
                new Leg("acct:b", com.technomorph.lck.spi.Model.EntryType.CREDIT, 10_000, "USD"),
                new Leg("acct:c", com.technomorph.lck.spi.Model.EntryType.DEBIT, 7_000, "EUR"),
                new Leg("acct:d", com.technomorph.lck.spi.Model.EntryType.CREDIT, 7_000, "EUR")));

        assertEquals(2, ps.size(), ps::toString);
        for (Posting p : ps) {
            long expected = p.asset().equals("USD") ? 10_000 : 7_000;
            assertEquals(expected, p.amount(), () -> "amount crossed a currency boundary: " + p);
        }
        // A posting whose source is a USD account and destination a EUR one would be a
        // fabricated FX trade invented by the harness.
        assertTrue(ps.stream().noneMatch(p ->
                (p.source().equals("acct:a") && p.destination().equals("acct:d"))
             || (p.source().equals("acct:c") && p.destination().equals("acct:b"))), ps::toString);
    }

    @ParameterizedTest(name = "seed {0}: postings preserve every account''s net movement")
    @ValueSource(longs = {1L, 42L, 1234L, 99991L})
    @DisplayName("whatever the pairing, each account moves exactly what its legs said")
    void pairingPreservesPerAccountNet(long seed) {
        Random rnd = new Random(seed);

        for (int trial = 0; trial < 200; trial++) {
            List<Leg> legs = balancedLegs(rnd);

            Map<String, Long> fromLegs = new HashMap<>();
            for (Leg l : legs)
                fromLegs.merge(l.accountId() + "|" + l.currency(), l.type()
                        == com.technomorph.lck.spi.Model.EntryType.CREDIT
                        ? l.amountSubunits() : -l.amountSubunits(), Long::sum);

            Map<String, Long> fromPostings = new HashMap<>();
            for (Posting p : FormanceLedgerAdapter.postings(legs)) {
                fromPostings.merge(p.source() + "|" + p.asset(), -p.amount(), Long::sum);
                fromPostings.merge(p.destination() + "|" + p.asset(), p.amount(), Long::sum);
            }

            fromLegs.values().removeIf(v -> v == 0);
            fromPostings.values().removeIf(v -> v == 0);
            assertEquals(fromLegs, fromPostings, () ->
                    "the pairing changed what an account moved.\n  legs:     " + legs
                            + "\n  expected: " + fromLegs + "\n  actual:   " + fromPostings);
        }
    }

    /** A balanced multi-leg transaction: debits and credits net to zero in each currency. */
    private static List<Leg> balancedLegs(Random rnd) {
        List<Leg> legs = new ArrayList<>();
        for (String ccy : rnd.nextBoolean() ? List.of("USD") : List.of("USD", "EUR")) {
            int debits = 1 + rnd.nextInt(3), credits = 1 + rnd.nextInt(3);
            long[] d = split(rnd, debits), c = split(rnd, credits);
            long total = Math.min(sum(d), sum(c));
            // Rescale so both sides carry the same total, which is what "balanced" means here.
            d = scaleTo(d, total); c = scaleTo(c, total);
            for (int i = 0; i < d.length; i++)
                legs.add(new Leg("d" + i + ":" + ccy,
                        com.technomorph.lck.spi.Model.EntryType.DEBIT, d[i], ccy));
            for (int i = 0; i < c.length; i++)
                legs.add(new Leg("c" + i + ":" + ccy,
                        com.technomorph.lck.spi.Model.EntryType.CREDIT, c[i], ccy));
        }
        return legs;
    }

    private static long[] split(Random rnd, int n) {
        long[] a = new long[n];
        for (int i = 0; i < n; i++) a[i] = 1 + rnd.nextInt(100_000);
        return a;
    }

    private static long sum(long[] a) { long s = 0; for (long x : a) s += x; return s; }

    /** Trims the last element so the array sums to exactly {@code target}. */
    private static long[] scaleTo(long[] a, long target) {
        long running = 0;
        for (int i = 0; i < a.length - 1; i++) {
            a[i] = Math.max(1, Math.min(a[i], target - running - (a.length - 1 - i)));
            running += a[i];
        }
        a[a.length - 1] = target - running;
        return a;
    }
}
