package com.technomorph.lck.examples.tigerbeetle;

import com.technomorph.lck.examples.tigerbeetle.TigerBeetleLedgerAdapter.Pair;
import com.technomorph.lck.examples.tigerbeetle.TigerBeetleLedgerAdapter.Paired;
import com.technomorph.lck.spi.Model.EntryType;
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
 * The one place the TigerBeetle adapter chooses rather than translates.
 *
 * <p>No Docker: this is arithmetic, and it is the arithmetic that went wrong. The adapter's first
 * draft dropped legs it could not pair, so an unbalanced transaction was trimmed to the part that
 * balanced and applied — the identical bug the Formance adapter had, written a second time. The
 * conformance run caught it, but only because INV-01 happens to submit such a transaction; a
 * failure here names the defect instead of pointing at an invariant number, and needs no cluster.
 *
 * <p>Pairing here differs from the Formance adapter's on purpose. Both match debits to credits
 * within a currency. This one then makes a second pass <em>across</em> currencies, deliberately
 * producing a transfer TigerBeetle will refuse itself, so INV-11 tests the ledger rather than the
 * adapter. Only what survives both passes is unpairable.
 */
class TigerBeetlePairingTest {

    @Test
    @DisplayName("the ordinary two-leg transfer is one transfer and no residue")
    void twoLegsAreOneTransfer() {
        Paired p = TigerBeetleLedgerAdapter.pair(List.of(
                Leg.debit("acct:a", 50_000), Leg.credit("acct:b", 50_000)));

        assertEquals(1, p.transfers().size(), () -> "expected one transfer, got " + p.transfers());
        assertTrue(p.unpaired().isEmpty(), () -> "nothing should be left over: " + p.unpaired());
        assertEquals("acct:a", p.transfers().get(0).debit());
        assertEquals("acct:b", p.transfers().get(0).credit());
        assertEquals(50_000, p.transfers().get(0).amount());
    }

    @Test
    @DisplayName("an unbalanced transaction leaves residue, and residue is never silently dropped")
    void unbalancedLeavesResidue() {
        // INV-01's transaction: debit 24.00 against a credit of 23.00. The adapter must not send
        // the 23.00 that happens to balance and call the transaction applied.
        Paired p = TigerBeetleLedgerAdapter.pair(List.of(
                Leg.debit("acct:a", 2_400), Leg.credit("acct:b", 2_300)));

        assertFalse(p.unpaired().isEmpty(), """
                The 1.00 with no counterparty must be reported. Dropping it is how a transaction \
                that creates money gets sent as one that does not, and INV-01 then passes a \
                ledger that never saw the transaction it was being tested with.""");
        assertTrue(p.unpaired().toString().contains("100"), () ->
                "the residue must quote the amount left over: " + p.unpaired());
    }

    @Test
    @DisplayName("same currency is matched before any cross-currency pairing is considered")
    void sameCurrencyWins() {
        // A leg order that would tempt a naive single pass into pairing USD against EUR first.
        Paired p = TigerBeetleLedgerAdapter.pair(List.of(
                new Leg("acct:usd-out", EntryType.DEBIT, 1_000, "USD"),
                new Leg("acct:eur-in", EntryType.CREDIT, 1_000, "EUR"),
                new Leg("acct:eur-out", EntryType.DEBIT, 1_000, "EUR"),
                new Leg("acct:usd-in", EntryType.CREDIT, 1_000, "USD")));

        assertTrue(p.unpaired().isEmpty(), () -> "everything balances per currency: " + p.unpaired());
        assertEquals(2, p.transfers().size(), () -> p.transfers().toString());
        for (Pair t : p.transfers())
            assertEquals(t.debitCurrency(), t.creditCurrency(), () ->
                    "a well-formed transaction must never produce a cross-currency transfer just "
                            + "because of the order its legs arrived in: " + t);
    }

    @Test
    @DisplayName("a genuinely cross-currency transaction is paired, so the ledger can refuse it")
    void crossCurrencyIsPairedNotDropped() {
        // INV-11's transaction: a USD debit closing a EUR credit, with no FX leg.
        Paired p = TigerBeetleLedgerAdapter.pair(List.of(
                new Leg("acct:a", EntryType.DEBIT, 5_000, "USD"),
                new Leg("acct:b", EntryType.CREDIT, 5_000, "EUR")));

        assertEquals(1, p.transfers().size(), """
                This must become a transfer, not residue. TigerBeetle refuses a transfer whose \
                accounts sit in different ledgers, so sending it makes INV-11 a result about the \
                ledger; refusing it here would make INV-11 a result about the adapter.""");
        assertTrue(p.unpaired().isEmpty(), () -> p.unpaired().toString());
        assertNotEquals(p.transfers().get(0).debitCurrency(), p.transfers().get(0).creditCurrency());
    }

    @ParameterizedTest(name = "seed {0}: pairing preserves every account''s net movement")
    @ValueSource(longs = {1L, 42L, 1234L, 99991L})
    @DisplayName("for a balanced transaction, each account moves exactly what its legs said")
    void pairingPreservesPerAccountNet(long seed) {
        Random rnd = new Random(seed);

        for (int trial = 0; trial < 200; trial++) {
            List<Leg> legs = balancedLegs(rnd);
            Paired p = TigerBeetleLedgerAdapter.pair(legs);

            assertTrue(p.unpaired().isEmpty(), () ->
                    "a balanced transaction must leave nothing over: " + legs + " -> " + p.unpaired());

            Map<String, Long> fromLegs = new HashMap<>();
            for (Leg l : legs)
                fromLegs.merge(l.accountId() + "|" + l.currency(),
                        l.type() == EntryType.CREDIT ? l.amountSubunits() : -l.amountSubunits(),
                        Long::sum);

            Map<String, Long> fromTransfers = new HashMap<>();
            for (Pair t : p.transfers()) {
                fromTransfers.merge(t.debit() + "|" + t.debitCurrency(), -t.amount(), Long::sum);
                fromTransfers.merge(t.credit() + "|" + t.creditCurrency(), t.amount(), Long::sum);
            }

            fromLegs.values().removeIf(v -> v == 0);
            fromTransfers.values().removeIf(v -> v == 0);
            assertEquals(fromLegs, fromTransfers, () ->
                    "the pairing changed what an account moved.\n  legs:     " + legs
                            + "\n  expected: " + fromLegs + "\n  actual:   " + fromTransfers);
        }
    }

    /** Debits and credits that net to zero within each currency. */
    private static List<Leg> balancedLegs(Random rnd) {
        List<Leg> legs = new ArrayList<>();
        for (String ccy : rnd.nextBoolean() ? List.of("USD") : List.of("USD", "EUR")) {
            long total = 1_000 + rnd.nextInt(100_000);
            legs.addAll(side(rnd, ccy, total, EntryType.DEBIT));
            legs.addAll(side(rnd, ccy, total, EntryType.CREDIT));
        }
        return legs;
    }

    /** Splits {@code total} across one to three legs, exactly. */
    private static List<Leg> side(Random rnd, String ccy, long total, EntryType type) {
        int n = 1 + rnd.nextInt(3);
        List<Leg> out = new ArrayList<>();
        long left = total;
        for (int i = 0; i < n - 1; i++) {
            long take = 1 + rnd.nextLong(Math.max(1, left - (n - 1 - i)));
            out.add(new Leg(type + ":" + i + ":" + ccy, type, take, ccy));
            left -= take;
        }
        out.add(new Leg(type + ":" + (n - 1) + ":" + ccy, type, left, ccy));
        return out;
    }
}
