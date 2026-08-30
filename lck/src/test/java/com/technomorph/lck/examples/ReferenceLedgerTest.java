package com.technomorph.lck.examples;

import com.technomorph.lck.spi.Model.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The reference ledger is the definition of correct that every invariant is vetted against, so
 * a defect in it is worse than a defect in an invariant: it is a defect in the standard.
 *
 * <p>The properties below are ones no invariant exercises. They were found by reading, and are
 * pinned here so they are not lost again. The false-duplicate test fails against the previous
 * implementation, which is what makes it worth having; the read-skew test is a regression net
 * for a window too narrow to reproduce on demand.
 */
class ReferenceLedgerTest {

    @Test
    @DisplayName("a concurrent duplicate of a transaction that gets rejected is never told DUPLICATE")
    void rejectedTransactionsDoNotProduceFalseDuplicates() throws Exception {
        ReferenceLedger led = new ReferenceLedger();
        led.seed("acct:a", 1_000);

        // Every one of these will be refused for insufficient funds. The naive implementation
        // claims the key first and withdraws the claim afterwards, so whichever submissions
        // arrive inside that window are told DUPLICATE — that their payment was already
        // applied — when in fact nothing was posted at all. That is a silently dropped
        // transfer, and it is the exact class of defect this kit sells itself on finding.
        List<PostResult> results = simultaneously(32, () ->
                led.post(Transaction.transfer("acct:a", "acct:b", 5_000, "same-key")));

        assertEquals(32, results.size());
        assertTrue(results.stream().noneMatch(r -> r.status() == PostStatus.DUPLICATE), () ->
                results.stream().filter(r -> r.status() == PostStatus.DUPLICATE).count()
                        + " of 32 submissions were told DUPLICATE for a transaction that was "
                        + "never applied — the caller now believes a payment succeeded");
        assertTrue(results.stream().allMatch(r -> r.status() == PostStatus.REJECTED),
                "an unfunded transfer must be refused, whoever asks and however many ask at once");
        assertEquals(1_000, led.balance("acct:a"), "the account must be untouched");
        assertEquals(0, led.balance("acct:b"));
    }

    @Test
    @DisplayName("a key released by a rejection can still be used by a later, funded attempt")
    void aWithdrawnClaimDoesNotBlockTheKeyForever() throws Exception {
        ReferenceLedger led = new ReferenceLedger();
        led.seed("acct:a", 1_000);

        assertEquals(PostStatus.REJECTED,
                led.post(Transaction.transfer("acct:a", "acct:b", 5_000, "retry-key")).status());

        led.seed("acct:a", 10_000);      // funds arrive, the client retries the same key

        assertEquals(PostStatus.APPLIED,
                led.post(Transaction.transfer("acct:a", "acct:b", 5_000, "retry-key")).status(),
                "a key that never took effect must not be permanently burned");
        assertEquals(5_000, led.balance("acct:b"));
    }

    @Test
    @DisplayName("the balance is never behind the journal, even mid-flight")
    void balanceNeverLagsTheJournal() throws Exception {
        ReferenceLedger led = new ReferenceLedger();
        AtomicBoolean writing = new AtomicBoolean(true);
        ConcurrentLinkedQueue<String> skew = new ConcurrentLinkedQueue<>();

        // Credits only, so the projection only ever grows. The reader takes the journal first
        // and the balance second: the balance is therefore read at a later instant, and must
        // have caught up. When the two were written under different locks there was a window
        // in which an entry was visible before its effect on the balance had landed — read skew
        // INV-08 cannot catch, because it reads after the writers have drained.
        //
        // Honest about what this test is: a regression net, not a reproduction. The window was
        // narrow enough that this test does not reliably fail against the previous
        // implementation — I checked. The guarantee comes from the commit path holding one
        // critical section over both writes; this catches a future edit that reopens it.
        Thread reader = Thread.ofVirtual().start(() -> {
            while (writing.get()) {
                long projected = led.journal().stream()
                        .filter(e -> e.accountId().equals("acct:hot"))
                        .mapToLong(JournalEntry::signed).sum();
                long actual = led.balance("acct:hot", "USD");
                if (actual < projected)
                    skew.add("journal showed " + projected + " subunits but the balance read "
                            + actual + " — short by " + (projected - actual));
            }
        });

        simultaneously(400, () -> led.post(new Transaction(UUID.randomUUID().toString(),
                List.of(Leg.debit("external:funding", 100), Leg.credit("acct:hot", 100)),
                UUID.randomUUID().toString(), true, Map.of())));

        writing.set(false);
        reader.join();

        assertTrue(skew.isEmpty(), () -> skew.size() + " observations of read skew, first: "
                + skew.peek());
        assertEquals(40_000, led.balance("acct:hot"));
    }

    // ------------------------------------------------------------------ plumbing

    private interface Post { PostResult run() throws Exception; }

    /** Releases every submission from one latch, so they contend rather than queue. */
    private static List<PostResult> simultaneously(int n, Post body) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<PostResult> results = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int i = 0; i < n; i++)
                exec.submit(() -> {
                    try {
                        start.await();
                        results.add(body.run());
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                });
            start.countDown();
            exec.shutdown();
            assertTrue(exec.awaitTermination(60, TimeUnit.SECONDS), "submissions did not complete");
        } finally {
            exec.shutdownNow();
        }
        assertTrue(failures.isEmpty(), () -> "a submission threw: " + failures.peek());
        return List.copyOf(results);
    }
}
