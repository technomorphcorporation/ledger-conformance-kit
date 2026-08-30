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
