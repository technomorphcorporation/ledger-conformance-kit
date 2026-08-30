package com.technomorph.lck.tck;

import com.technomorph.lck.examples.ReferenceLedger;
import com.technomorph.lck.spi.Capability;
import com.technomorph.lck.spi.LedgerAdapter;
import com.technomorph.lck.spi.Model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The TCK is the thing standing between a client's wiring mistake and a false finding. */
class AdapterTckTest {

    @Test
    @DisplayName("a correct adapter passes every TCK check")
    void correctAdapterPasses() {
        List<AdapterTck.Finding> fs = AdapterTck.verify(new ReferenceLedger());
        assertTrue(AdapterTck.trustworthy(fs), () -> fs.stream().filter(f -> !f.ok())
                .map(f -> f.id() + " " + f.detail()).reduce((a, b) -> a + "; " + b).orElse(""));
    }

    @Test
    @DisplayName("an adapter reporting dollars instead of subunits is caught")
    void majorUnitsAreCaught() {
        LedgerAdapter wrong = new Delegating() {
            @Override public long balance(String a, String c) throws Exception {
                return inner.balance(a, c) / 100;      // the classic adapter bug
            }
        };
        List<AdapterTck.Finding> fs = AdapterTck.verify(wrong);
        assertFalse(AdapterTck.trustworthy(fs));
        assertTrue(fs.stream().anyMatch(f -> f.id().equals("TCK-02") && !f.ok()));
    }

    @Test
    @DisplayName("an adapter that throws on business rejection is caught")
    void exceptionOnRejectionIsCaught() {
        LedgerAdapter wrong = new Delegating() {
            @Override public PostResult post(Transaction t) throws Exception {
                PostResult r = inner.post(t);
                if (r.status() == PostStatus.REJECTED) throw new IllegalStateException("insufficient funds");
                return r;
            }
        };
        assertFalse(AdapterTck.trustworthy(AdapterTck.verify(wrong)));
    }

    @Test
    @DisplayName("a non-empty environment is refused before anything is reset")
    void nonEmptyEnvironmentIsRefusedBeforeAnyWrite() {
        PopulatedLedger populated = new PopulatedLedger();

        List<AdapterTck.Finding> fs = AdapterTck.verify(populated);

        assertFalse(AdapterTck.trustworthy(fs), "an environment with existing data must not be trusted");
        assertEquals(0, populated.resets, "the TCK reset an environment it had already judged non-empty");
        assertEquals(1, fs.size(), "no check may run after the scratch check has refused");
        assertEquals("TCK-00", fs.get(0).id());
        // two legs from the seed, two from the transfer
        assertTrue(fs.get(0).detail().contains("4 entries"),
                () -> "the refusal must say how much data it found: " + fs.get(0).detail());
    }

    @Test
    @DisplayName("the scratch check is the first thing the TCK does")
    void scratchCheckRunsFirst() {
        List<AdapterTck.Finding> fs = AdapterTck.verify(new ReferenceLedger());
        assertEquals("TCK-00", fs.get(0).id(),
                "TCK-00 must run before any check that calls reset(), or it can only confirm "
                        + "the wipe it exists to prevent");
    }

    /** Already holds data, and counts every reset() so the test can prove none happened. */
    static final class PopulatedLedger extends Delegating {
        int resets;

        PopulatedLedger() {
            try {
                inner.seed("acct:existing", 100_000);
                inner.post(Transaction.transfer("acct:existing", "acct:other", 2_500, "prior-activity"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override public void reset() throws Exception { resets++; super.reset(); }
    }

    abstract static class Delegating implements LedgerAdapter {
        protected final ReferenceLedger inner = new ReferenceLedger();
        @Override public String name() { return "deliberately-wrong-adapter"; }
        @Override public Set<Capability> capabilities() { return inner.capabilities(); }
        @Override public void reset() throws Exception { inner.reset(); }
        @Override public PostResult post(Transaction t) throws Exception { return inner.post(t); }
        @Override public long balance(String a, String c) throws Exception { return inner.balance(a, c); }
        @Override public List<JournalEntry> journal() throws Exception { return inner.journal(); }
    }
}
