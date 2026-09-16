package com.technomorph.lck.examples.tigerbeetle;

import com.technomorph.lck.core.Invariants;
import com.technomorph.lck.core.Invariants.Result;
import com.technomorph.lck.core.Invariants.Status;
import com.technomorph.lck.report.Reports;
import com.technomorph.lck.tck.AdapterTck;

import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The suite against TigerBeetle. This is the calibration run.
 *
 * <p>Its purpose is the opposite of a finding. TigerBeetle is built by people who treat
 * correctness as the product — deterministic simulation testing, strict serializability, a
 * consensus protocol — so the prior on a genuine defect here is low, and the prior on a defect in
 * <em>this kit</em> is not. If an invariant fails, the first hypothesis is that the invariant or
 * the adapter is wrong, and that hypothesis has to be eliminated before the result is repeated
 * anywhere.
 *
 * <p>Which makes a clean run the most valuable thing this file can produce: it is the strongest
 * available evidence that the suite does not manufacture findings, and that is what licenses
 * every finding reported against any other ledger.
 *
 * <p>As with the Formance run, <b>this test does not assert which invariants hold</b> — that
 * would be a guess about somebody else's ledger, recorded as though it were a requirement. It
 * asserts the three things that are ours to be right about: the adapter passes the TCK, nothing
 * ends in {@link Status#ERROR} (an exception escaping the adapter is our defect), and nothing
 * ends in {@link Status#INFRA} (a cluster on this host going unreachable means the harness is
 * wrong about its environment).
 */
@Tag("docker")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TigerBeetleConformanceTest {

    private static final long SEED = Long.getLong("lck.seed", 42L);

    private static final Tb tb = new Tb();
    private static TigerBeetleLedgerAdapter adapter;

    @BeforeAll
    static void start() {
        Assumptions.assumeTrue(Tb.dockerAvailable(),
                "Docker is not available; the TigerBeetle run is skipped, not failed");
        Tb.assertVersionsMatch();
        adapter = new TigerBeetleLedgerAdapter(tb.start());
    }

    @AfterAll
    static void stop() { tb.close(); }

    @Test
    @Order(1)
    @DisplayName("the adapter passes the TCK, so findings below are about TigerBeetle and not about us")
    void adapterIsTrustworthy() throws Exception {
        List<AdapterTck.Finding> checks = AdapterTck.verify(adapter);

        assertTrue(AdapterTck.trustworthy(checks), () ->
                "the TCK failed, so the adapter and the kit disagree about the contract. Against "
                        + "this ledger in particular that is the likely explanation for any "
                        + "failure, and none of it may be reported as a finding:\n  "
                        + String.join("\n  ", checks.stream().filter(c -> !c.ok())
                        .map(c -> c.id() + " " + c.requirement() + ": " + c.detail()).toList()));
    }

    @Test
    @Order(2)
    @DisplayName("the suite runs to completion against a live TigerBeetle cluster")
    void suiteRuns() throws Exception {
        List<Result> results = Invariants.run(adapter, SEED);
        report(results);

        List<Result> errored = results.stream().filter(r -> r.status() == Status.ERROR).toList();
        assertTrue(errored.isEmpty(), () ->
                "an exception escaped the adapter, which is a defect in "
                        + "TigerBeetleLedgerAdapter and not in TigerBeetle:\n  "
                        + errored.stream().map(r -> r.id() + ": " + r.detail()).toList());

        List<Result> infra = results.stream().filter(r -> r.status() == Status.INFRA).toList();
        assertTrue(infra.isEmpty(), () ->
                "the cluster became unreachable mid-run. On a container on this host that is the "
                        + "harness being wrong about its environment, not a finding:\n  "
                        + infra.stream().map(r -> r.id() + ": " + r.detail()).toList());

        System.out.println("\n--- TigerBeetle " + Tb.VERSION + ", seed " + SEED + " ---");
        for (Result r : results)
            System.out.printf("  %-8s %-6s %s%n", r.id(), r.status(),
                    r.detail() == null ? "" : r.detail());

        List<Result> broke = results.stream().filter(Result::broke).toList();
        if (!broke.isEmpty())
            // Printed loudly rather than asserted. A failure here is a claim that the ledger most
            // associated with correctness has a correctness defect, which is not a claim to make
            // from one run of a young suite through a hand-written adapter.
            System.out.println("\n  NOT A FINDING UNTIL DISPROVED AS OURS: " + broke.stream()
                    .map(Result::id).toList() + " — check the invariant and the adapter first.");
    }

    @Test
    @Order(3)
    @DisplayName("journal() pages past one query's limit rather than stopping at it")
    void journalPagesToExhaustion() throws Exception {
        adapter.reset();

        int txns = 300;
        for (int i = 0; i < txns; i++)
            adapter.post(new com.technomorph.lck.spi.Model.Transaction(
                    "page-" + i,
                    List.of(com.technomorph.lck.spi.Model.Leg.debit("external:funding", 100),
                            com.technomorph.lck.spi.Model.Leg.credit("acct:paged", 100)),
                    "page-txn-" + i, true, java.util.Map.of()));

        // One transfer is a debit and a credit, so the kit sees two entries per transaction.
        assertEquals(txns * 2, adapter.journal().size(),
                "a short read here would look like INV-13 finding a ledger that cannot rebuild "
                        + "its own balances, rather than like a pagination bug in the harness");
        assertEquals(txns * 100L, adapter.balance("acct:paged", "USD"));
        assertEquals(adapter.balance("acct:paged", "USD"),
                adapter.replayBalance("acct:paged", "USD"),
                "the journal must rebuild the balance exactly across the page boundary");
    }

    @Test
    @Order(4)
    @DisplayName("reset() makes earlier generations invisible without destroying them")
    void resetIsolatesWithoutDeleting() throws Exception {
        adapter.reset();
        adapter.post(new com.technomorph.lck.spi.Model.Transaction(
                "gen-a", List.of(com.technomorph.lck.spi.Model.Leg.debit("external:funding", 900),
                com.technomorph.lck.spi.Model.Leg.credit("acct:x", 900)),
                "gen-a-txn", true, java.util.Map.of()));
        assertEquals(900, adapter.balance("acct:x", "USD"));

        adapter.reset();

        // TigerBeetle cannot delete, so the previous generation is still on disk. What must be
        // true is that it is unreachable: a fresh namespace means acct:x is an account the
        // cluster has never seen, and the journal filter cannot match the old transfers.
        assertEquals(0, adapter.balance("acct:x", "USD"),
                "after a reset the same account name must resolve to a new, empty account");
        assertTrue(adapter.journal().isEmpty(),
                "the journal must show this generation only — TCK-00 depends on it");
    }

    private static void report(List<Result> results) throws Exception {
        Path dir = Path.of("build", "reports", "lck");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("tigerbeetle.html"), Reports.html(results, "tigerbeetle"));
        Files.writeString(dir.resolve("tigerbeetle.json"), Reports.json(results, "tigerbeetle"));
    }
}
