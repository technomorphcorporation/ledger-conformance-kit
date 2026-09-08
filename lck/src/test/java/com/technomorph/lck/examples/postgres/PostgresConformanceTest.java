package com.technomorph.lck.examples.postgres;

import com.technomorph.lck.core.Invariants;
import com.technomorph.lck.core.Invariants.Result;
import com.technomorph.lck.core.Invariants.Status;
import com.technomorph.lck.tck.AdapterTck;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The same ledger, wrong and then right, against a real PostgreSQL.
 *
 * <p>This exists because the fixes are database behaviour and cannot be demonstrated anywhere
 * else. Whether a unique index actually stops a concurrent duplicate, whether a conditional
 * UPDATE is atomic, whether READ COMMITTED lets two transactions act on the same read — none of
 * that is observable against an in-memory structure, which has no isolation level at all. An
 * in-memory ledger that passes proves the harness works, not that the SQL does.
 *
 * <p>Skips rather than fails when Docker is unavailable, so it does not punish a contributor
 * who does not have it running.
 */
@Tag("docker")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostgresConformanceTest {

    private static final long SEED = 42L;

    private static PostgreSQLContainer<?> container;
    private static Pg pg;

    @BeforeAll
    static void start() throws Exception {
        Assumptions.assumeTrue(dockerAvailable(),
                "Docker is not available; the Postgres example is skipped, not failed");
        container = new PostgreSQLContainer<>("postgres:16-alpine");
        container.start();
        pg = new Pg(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    /**
     * Availability, not reachability-or-explosion. {@code isDockerAvailable()} throws when it
     * cannot find a daemon rather than returning false — on macOS the socket lives under
     * {@code ~/.docker/run} and is not where it looks, so a developer with Docker running gets a
     * failed build. The rule is that this skips without Docker; a guard that can throw does not
     * keep it. Set {@code DOCKER_HOST} if the daemon is somewhere non-standard.
     */
    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @AfterAll
    static void stop() {
        if (pg != null) pg.close();
        if (container != null) container.stop();
    }

    @Test
    @Order(1)
    @DisplayName("the naive ledger passes the TCK — it is wired correctly and still wrong")
    void naiveIsAConformantAdapterAndStillBroken() throws Exception {
        NaivePostgresLedger naive = new NaivePostgresLedger(pg);

        assertTrue(AdapterTck.trustworthy(AdapterTck.verify(naive)),
                "the point of this example is a correctly wired adapter over a defective ledger; "
                        + "if the TCK fails, the findings below would be about the adapter instead");

        Set<String> broke = broke(Invariants.run(naive, SEED));
        report("naive-postgres", Invariants.run(naive, SEED));

        // Two of the three defences cost exactly what the article says they cost.
        assertTrue(broke.contains("INV-05"),
                "check-then-act on the key must lose the duplicate race: the check is in "
                        + "application code and nothing in the schema stops a second writer");
        assertTrue(broke.contains("INV-09"),
                "a funds check that runs before the write must let the account overdraw");
        assertTrue(broke.contains("INV-08"),
                "a balance written from a stale read must diverge from the journal");

        // The third does not, and this is the most useful thing this example demonstrates.
        //
        // Read-modify-write on a balance is a lost update everywhere except here. Every credit
        // in INV-06 debits external:funding first, and this ledger takes a row lock on it — so
        // all five hundred transactions queue on one row, only one is ever between reading
        // acct:hot and committing, and the update it would have lost is never lost. The ledger
        // is accidentally correct because it is accidentally serial.
        //
        // Two things follow. A concurrency invariant can be satisfied by a ledger that has no
        // concurrency, which is why INV-06 reports the applied count rather than a bare verdict.
        // And the same logical defect surfaces or hides depending on what else the transaction
        // touches: the reference ledger shards external:funding sixteen ways precisely so it
        // does not serialise, and sharding it is what would expose this bug.
        assertFalse(broke.contains("INV-06"),
                "if this now fails, the accidental serialisation on external:funding has gone "
                        + "and the comment above needs revisiting rather than the assertion");

        // INV-15 passes, and for a reason worth stating rather than leaving to be rediscovered.
        // It fails a ledger whose idempotency record collapses IN_FLIGHT into COMMITTED — one
        // that answers "already applied" for a transaction still in flight, which is then
        // refused. This ledger has no idempotency record at all beyond a table with no
        // constraint, so it never answers DUPLICATE and has nothing to collapse. INV-04 and
        // INV-05 are what catch that; INV-15 is aimed at the ledger that has tried and stopped
        // one state short.
        assertFalse(broke.contains("INV-15"),
                "a ledger with no idempotency cannot exhibit the two-state bug; INV-04 and "
                        + "INV-05 are the ones that catch having none");

        // INV-07 and INV-13 deadlock here — this ledger applies legs in whatever order the
        // transaction lists them, with no total order over accounts. Deliberately not asserted:
        // whether a deadlock is detected is timing, and a flaky assertion in the example that
        // demonstrates rigour would be its own kind of finding.
        assertTrue(broke.size() >= 3, () -> "expected the naive ledger to break several: " + broke);
    }

    @Test
    @Order(2)
    @DisplayName("the same ledger with the three fixes holds every applicable invariant")
    void correctedLedgerHolds() throws Exception {
        PostgresLedger fixed = new PostgresLedger(pg);

        assertTrue(AdapterTck.trustworthy(AdapterTck.verify(fixed)));

        List<Result> rs = Invariants.run(fixed, SEED);
        report("postgres", rs);

        List<String> failed = rs.stream().filter(Result::broke)
                .map(r -> r.id() + " — " + r.detail()).toList();
        assertTrue(failed.isEmpty(), () ->
                "the corrected ledger must hold every invariant that applies to it: " + failed);

        // Named rather than merely covered by the blanket assertion above: INV-15 is the check
        // for the third state, and the three-state idempotency record is one of the four fixes
        // this ledger exists to demonstrate. If it ever regresses, the failure should say which
        // fix stopped working.
        assertEquals(Status.PASS,
                rs.stream().filter(r -> r.id().equals("INV-15")).findFirst().orElseThrow().status(),
                "the three-state idempotency record is what INV-15 checks");
    }

    private static Set<String> broke(List<Result> rs) {
        Set<String> out = new TreeSet<>();
        for (Result r : rs) if (r.broke()) out.add(r.id());
        return out;
    }

    /**
     * Written to a file rather than printed: a test runner swallows stdout, and the whole
     * purpose of this example is that the numbers come from a run against a real database
     * rather than from memory. CI already collects build/reports/lck.
     */
    private static void report(String label, List<Result> rs) throws Exception {
        StringBuilder sb = new StringBuilder()
                .append(label).append("   seed=").append(SEED)
                .append("   connection pool=").append(Pg.POOL)
                .append(System.lineSeparator());
        for (Result r : rs)
            sb.append(String.format("  %-8s %-6s %-8s %s%n",
                    r.id(), r.status(), r.severity(), r.detail()));
        sb.append(String.format("  held %d · broke %d · not applicable %d%n",
                rs.stream().filter(r -> r.status() == Status.PASS).count(),
                rs.stream().filter(Result::broke).count(),
                rs.stream().filter(r -> r.status() == Status.SKIP).count()));

        Path dir = Path.of("build/reports/lck");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(label + "-conformance.txt"), sb);
        System.out.print(sb);
    }
}
