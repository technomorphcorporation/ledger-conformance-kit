package com.technomorph.lck.core;

import com.technomorph.lck.core.Invariants.Check;
import com.technomorph.lck.core.Invariants.Invariant;
import com.technomorph.lck.examples.ReferenceLedger;
import com.technomorph.lck.spi.Capability;
import com.technomorph.lck.spi.LedgerAdapter;
import com.technomorph.lck.spi.Model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * INV-02, "the journal is append-only", in isolation.
 *
 * <p>The append-only check is one of two invariants whose failure branches historically
 * carried no number, in violation of the kit's own first rule: a finding is a defect
 * report, and a defect report has a figure in it. These tests pin both failure branches
 * to a numbered message so a future edit cannot quietly regress to "history was rewritten"
 * with nothing an engineer can act on.
 *
 * <p>The two ways to break append-only are exercised with the minimum defect that trips
 * each: a journal that stops growing, and a journal whose earlier entries move.
 */
class AppendOnlyInvariantTest {

    private static final long SEED = 42L;
    private static final Pattern HAS_DIGIT = Pattern.compile("\\d");

    @Test
    @DisplayName("a correct ledger holds append-only and says so with a count")
    void referenceLedgerHolds() throws Exception {
        Check c = runInv02(new ReferenceLedger());
        assertTrue(c.held(), () -> "INV-02 failed a correct ledger: " + c.detail());
        assertTrue(c.detail().contains("prefix preserved"), c::detail);
        assertTrue(HAS_DIGIT.matcher(c.detail()).find(),
                () -> "even the passing message should quote the entry count: " + c.detail());
    }

    @Test
    @DisplayName("a journal that stops growing fails, reporting the entry count it held at")
    void frozenJournalFailsWithCount() throws Exception {
        Check c = runInv02(new FrozenJournal());
        assertFalse(c.held(), "a journal that ignored two further transactions must fail INV-02");
        // seed (2 entries) + one transfer (2 entries) = 4 before the further posts.
        assertTrue(c.detail().contains("held at 4 entries"), c::detail);
        assertTrue(c.detail().contains("expected at least 5"), c::detail);
        assertTrue(HAS_DIGIT.matcher(c.detail()).find(), c::detail);
    }

    @Test
    @DisplayName("a journal whose earlier entries move fails, naming the entry that diverged")
    void rewrittenPrefixFailsWithIndex() throws Exception {
        Check c = runInv02(new ReorderedHistory());
        assertFalse(c.held(), "a journal that reordered its first two entries must fail INV-02");
        assertTrue(c.detail().contains("diverged at entry 0 of 4"), c::detail);
        assertTrue(HAS_DIGIT.matcher(c.detail()).find(), c::detail);
    }

    @Test
    @DisplayName("every INV-02 failure message carries a number, not an adjective")
    void everyFailureDetailIsNumbered() throws Exception {
        for (LedgerAdapter broken : List.of(new FrozenJournal(), new ReorderedHistory())) {
            Check c = runInv02(broken);
            assertFalse(c.held(), () -> broken.name() + " should have failed INV-02");
            assertTrue(HAS_DIGIT.matcher(c.detail()).find(),
                    () -> broken.name() + " reported a numberless finding: \"" + c.detail()
                            + "\" — rule 5 says a finding is a number, an opinion is a boolean");
        }
    }

    // ---------------------------------------------------------------- plumbing

    /** Run INV-02 alone, exactly as {@link Invariants#run} would: reset, then the body. */
    private static Check runInv02(LedgerAdapter led) throws Exception {
        Invariant inv = Invariants.REGISTRY.stream()
                .filter(i -> i.id().equals("INV-02")).findFirst().orElseThrow();
        led.reset();
        return inv.body().run(led, new Invariants.Harness(SEED));
    }

    /** A correct ledger seen through composition, so its journal view can be sabotaged below. */
    private static class DelegatingLedger implements LedgerAdapter {
        protected final ReferenceLedger ref = new ReferenceLedger();
        @Override public String name() { return "test:" + getClass().getSimpleName(); }
        @Override public Set<Capability> capabilities() { return ref.capabilities(); }
        @Override public void reset() throws Exception { ref.reset(); }
        @Override public PostResult post(Transaction t) throws Exception { return ref.post(t); }
        @Override public long balance(String a, String c) throws Exception { return ref.balance(a, c); }
        @Override public List<JournalEntry> journal() throws Exception { return ref.journal(); }
    }

    /** Writes still commit, but the journal view is frozen at first read: it never grows. */
    private static final class FrozenJournal extends DelegatingLedger {
        private List<JournalEntry> frozen;
        @Override public void reset() throws Exception { super.reset(); frozen = null; }
        @Override public List<JournalEntry> journal() throws Exception {
            if (frozen == null) frozen = List.copyOf(ref.journal());
            return frozen;
        }
    }

    /** History is rewritten under the reader: the first two entries swap after the first read. */
    private static final class ReorderedHistory extends DelegatingLedger {
        private int reads;
        @Override public void reset() throws Exception { super.reset(); reads = 0; }
        @Override public List<JournalEntry> journal() throws Exception {
            List<JournalEntry> view = new ArrayList<>(ref.journal());
            if (++reads >= 2 && view.size() >= 2) Collections.swap(view, 0, 1);
            return view;
        }
    }
}
