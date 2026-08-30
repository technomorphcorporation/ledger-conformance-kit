package com.technomorph.lck.spi;

import com.technomorph.lck.spi.Model.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The SPI is what clients compile against and its public surface is MAJOR-versioned, so every
 * rule below is one that can be added today and never again without a breaking release.
 */
class ModelTest {

    private static Leg debit(long amt)  { return Leg.debit("acct:a", amt); }
    private static Leg credit(long amt) { return Leg.credit("acct:b", amt); }

    @Test
    @DisplayName("a transaction copies its legs, so the caller cannot mutate it afterwards")
    void legsAreCopied() {
        List<Leg> mutable = new ArrayList<>(List.of(debit(500), credit(500)));

        Transaction txn = new Transaction("k", mutable, "t", false, Map.of());
        mutable.add(credit(9_999));

        assertEquals(2, txn.legs().size(), "the transaction changed under a caller who kept the list");
        assertEquals(Map.of("USD", 0L), txn.netByCurrency(), "and it is still balanced");
        assertThrows(UnsupportedOperationException.class, () -> txn.legs().add(credit(1)),
                "the list handed out must not be writable either");
    }

    @Test
    @DisplayName("metadata is copied too")
    void metadataIsCopied() {
        Map<String, String> mutable = new HashMap<>(Map.of("ref", "INV-99"));

        Transaction txn = new Transaction("k", List.of(debit(500), credit(500)), "t", false, mutable);
        mutable.put("ref", "tampered");

        assertEquals("INV-99", txn.metadata().get("ref"));
    }

    @Test
    @DisplayName("a transaction with no legs is refused, not accepted as a no-op")
    void emptyLegsAreRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Transaction("empty-key", List.of(), "t", false, Map.of()));
        assertTrue(e.getMessage().contains("empty-key"),
                () -> "the message should name the transaction: " + e.getMessage());
    }

    @Test
    @DisplayName("the required fields are required")
    void nullsAreRefused() {
        List<Leg> legs = List.of(debit(500), credit(500));
        assertThrows(NullPointerException.class, () -> new Transaction(null, legs, "t", false, Map.of()));
        assertThrows(NullPointerException.class, () -> new Transaction("k", null, "t", false, Map.of()));
        assertThrows(NullPointerException.class, () -> new Transaction("k", legs, null, false, Map.of()));
        assertThrows(NullPointerException.class, () -> new Transaction("k", legs, "t", false, null));
        assertThrows(NullPointerException.class, () -> new Leg(null, EntryType.DEBIT, 1, "USD"));
        assertThrows(NullPointerException.class, () -> new Leg("acct:a", EntryType.DEBIT, 1, null));
    }

    @Test
    @DisplayName("a zero or negative leg is refused, and the message says which account")
    void unsignedLegs() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> debit(0));
        assertTrue(e.getMessage().contains("acct:a"), e::getMessage);
        assertThrows(IllegalArgumentException.class, () -> debit(-1));
    }

    @Test
    @DisplayName("netByCurrency throws on overflow rather than wrapping into a plausible number")
    void overflowThrowsRatherThanWrapping() {
        // Two credits that together exceed Long.MAX_VALUE. Summed with Long::sum this wraps
        // negative, so a transaction moving more money than exists reads as merely unbalanced
        // by a modest amount — and with the right pair of figures, as balanced.
        Transaction txn = new Transaction("k",
                List.of(new Leg("acct:a", EntryType.CREDIT, Long.MAX_VALUE, "USD"),
                        new Leg("acct:b", EntryType.CREDIT, Long.MAX_VALUE, "USD")),
                "t", true, Map.of());

        assertThrows(ArithmeticException.class, txn::netByCurrency,
                "a silent wrap in the method whose job is deciding whether a transaction "
                        + "balances is the worst possible place for one");
    }

    @Test
    @DisplayName("currencies are netted separately, not against each other")
    void netIsPerCurrency() {
        Transaction txn = new Transaction("fx",
                List.of(new Leg("acct:a", EntryType.DEBIT, 1_000, "USD"),
                        new Leg("acct:b", EntryType.CREDIT, 1_000, "EUR")),
                "t", true, Map.of());

        assertEquals(Map.of("USD", -1_000L, "EUR", 1_000L), txn.netByCurrency());
    }

    @Test
    @DisplayName("an adapter that needs no teardown does not have to write one")
    void closeIsANoOpByDefault() {
        LedgerAdapter minimal = new LedgerAdapter() {
            @Override public String name() { return "minimal"; }
            @Override public void reset() { }
            @Override public PostResult post(Transaction t) { return PostResult.applied(t.transactionId()); }
            @Override public long balance(String a, String c) { return 0; }
            @Override public List<JournalEntry> journal() { return List.of(); }
        };
        assertDoesNotThrow(() -> { try (LedgerAdapter led = minimal) { assertEquals("minimal", led.name()); } });
    }
}
