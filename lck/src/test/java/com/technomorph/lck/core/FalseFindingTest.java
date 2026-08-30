package com.technomorph.lck.core;

import com.technomorph.lck.core.Invariants.Invariant;
import com.technomorph.lck.core.Invariants.Result;
import com.technomorph.lck.core.Invariants.Status;
import com.technomorph.lck.examples.ReferenceLedger;
import com.technomorph.lck.spi.Capability;
import com.technomorph.lck.spi.LedgerAdapter;
import com.technomorph.lck.spi.Model.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The other direction from {@link MutationTest}: not "does the suite catch a defect", but
 * "does the suite stay quiet about a ledger that has none".
 *
 * <p>A ledger at SERIALIZABLE isolation refuses writes that conflict — Postgres raises 40001
 * and the caller retries. It loses nothing: a refused write posts nothing. Until these tests
 * existed the suite submitted 500 transactions, asserted on 500, and reported the shortfall as
 * <em>"lost 20.00"</em> — confident, numeric, and wrong, against precisely the kind of ledger
 * the kit is pointed at. The client's engineers would have demonstrated it, and they would
 * have been right.
 */
class FalseFindingTest {

    private static final long SEED = 42L;

    private static Invariant byId(String id) {
        return Invariants.REGISTRY.stream().filter(i -> i.id().equals(id))
                .findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ write conflicts

    @ParameterizedTest(name = "{0} holds against a ledger that refuses conflicting writes")
    @ValueSource(strings = {"INV-05", "INV-06", "INV-07", "INV-09", "INV-10"})
    @DisplayName("a ledger that rejects under contention is not accused of losing money")
    void conflictRejectionIsNotALostUpdate(String id) {
        Result r = Invariants.runOne(new ConflictingLedger(), byId(id), SEED);

        assertEquals(Status.PASS, r.status(), () ->
                id + " failed a ledger that refused writes but lost nothing. A refused write "
                        + "posts nothing, so the shortfall is not a defect: " + r.detail());
    }

    @Test
    @DisplayName("the finding counts what the ledger applied, not what was submitted")
    void messagesReportTheAppliedCount() {
        ConflictingLedger led = new ConflictingLedger();
        Result r = Invariants.runOne(led, byId("INV-06"), SEED);

        assertEquals(Status.PASS, r.status(), r::detail);
        assertTrue(r.detail().contains(" of 500 "), () ->
                "the message must say how many of the 500 credits applied: " + r.detail());
        assertTrue(r.detail().contains("rejected"), () ->
                "rejections are an observation the reader needs, not something to hide: " + r.detail());
        assertFalse(r.detail().contains("lost"), () ->
                "nothing was lost and the message must not say so: " + r.detail());
    }

    @Test
    @DisplayName("a genuine lost update is still caught when writes are also being refused")
    void realDefectsSurviveTheRelaxation() {
        // The relaxation must not become a hiding place: this ledger both refuses writes AND
        // drops a hundred subunits from what it did apply.
        LedgerAdapter leaky = new ConflictingLedger() {
            @Override public long balance(String a, String c) throws Exception {
                long real = super.balance(a, c);
                return a.equals("acct:hot") ? real - 100 : real;
            }
        };
        Result r = Invariants.runOne(leaky, byId("INV-06"), SEED);

        assertEquals(Status.FAIL, r.status(), "a real shortfall against the applied count must fail");
        assertTrue(r.detail().contains("lost 1.00"), () ->
                "the finding must still quote the discrepancy in currency: " + r.detail());
    }

    @Test
    @DisplayName("INV-09 fails a guard that lets the account go negative, however many applied")
    void overdraftIsStillCaught() {
        LedgerAdapter overdrawing = new Delegating() {
            @Override public PostResult post(Transaction t) throws Exception {
                // The guard is ignored entirely: every debit is allowed through.
                return inner.post(new Transaction(t.idempotencyKey(), t.legs(),
                        t.transactionId(), true, t.metadata()));
            }
        };
        Result r = Invariants.runOne(overdrawing, byId("INV-09"), SEED);

        assertEquals(Status.FAIL, r.status(), "an account driven negative must always fail INV-09");
        assertTrue(r.detail().contains("overdrew to -"), r::detail);
    }

    // ------------------------------------------------------------------ infrastructure

    @Test
    @DisplayName("an unreachable ledger reports INFRA, which is neither a pass nor a finding")
    void transportFailureIsNotAFinding() {
        LedgerAdapter unreachable = new Delegating() {
            @Override public PostResult post(Transaction t) throws IOException {
                throw new IOException("Connection reset by peer");
            }
        };
        Result r = Invariants.runOne(unreachable, byId("INV-06"), SEED);

        assertEquals(Status.INFRA, r.status(),
                () -> "a dropped connection is not a defect in the ledger: " + r.detail());
        assertFalse(r.broke(), "INFRA must not count towards blocker failures");
        assertEquals(0, Invariants.blockerFailures(List.of(r)));
    }

    @Test
    @DisplayName("a ledger that throws for its own reasons is still an ERROR finding")
    void ledgerSideExceptionsAreStillFindings() {
        LedgerAdapter broken = new Delegating() {
            @Override public PostResult post(Transaction t) {
                throw new IllegalStateException("deadlock detected in posting engine");
            }
        };
        Result r = Invariants.runOne(broken, byId("INV-06"), SEED);

        assertEquals(Status.ERROR, r.status(), r::detail);
        assertTrue(r.broke(), "a crashing ledger is a finding");
        assertTrue(r.detail().contains("deadlock detected"), r::detail);
    }

    @Test
    @DisplayName("a crash detail names the frame, so it carries a line number like every other finding")
    void crashDetailsCarryAFrame() {
        LedgerAdapter npe = new Delegating() {
            @Override public PostResult post(Transaction t) {
                throw new NullPointerException();      // the classic message-less throwable
            }
        };
        Result r = Invariants.runOne(npe, byId("INV-06"), SEED);

        assertTrue(r.detail().startsWith("NullPointerException"), r::detail);
        assertFalse(r.detail().contains("null"), () ->
                "a detail reading 'NullPointerException: null' tells the reader nothing: " + r.detail());
        assertTrue(r.detail().contains("FalseFindingTest.java:"), () ->
                "the detail must name where it happened: " + r.detail());
    }

    @Test
    @DisplayName("an Error in the harness is not reported as the client's defect")
    void errorsPropagateRatherThanBecomingFindings() {
        LedgerAdapter oom = new Delegating() {
            @Override public PostResult post(Transaction t) {
                throw new OutOfMemoryError("Java heap space");
            }
        };
        assertThrows(OutOfMemoryError.class, () -> Invariants.runOne(oom, byId("INV-06"), SEED),
                "an OutOfMemoryError in the harness must not be recorded as a BLOCKER against "
                        + "the ledger under test");
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * Correct, and conservative: every third write is refused the way a serializable engine
     * refuses one, before the transaction reaches the ledger, so it claims no idempotency key
     * and posts nothing. Deterministic in count, which is all the assertions depend on.
     */
    static class ConflictingLedger extends Delegating {
        private final AtomicInteger seen = new AtomicInteger();

        @Override public PostResult post(Transaction t) throws Exception {
            if (seen.incrementAndGet() % 3 == 0)
                return PostResult.rejected(t.transactionId(), "serialization failure (40001)");
            return inner.post(t);
        }
    }

    abstract static class Delegating implements LedgerAdapter {
        protected final ReferenceLedger inner = new ReferenceLedger();
        @Override public String name() { return "conflicting-ledger"; }
        @Override public Set<Capability> capabilities() { return inner.capabilities(); }
        @Override public void reset() { inner.reset(); }
        @Override public PostResult post(Transaction t) throws Exception { return inner.post(t); }
        @Override public long balance(String a, String c) throws Exception { return inner.balance(a, c); }
        @Override public List<JournalEntry> journal() { return inner.journal(); }
    }
}
