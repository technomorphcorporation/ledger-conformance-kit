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

        // The three defences in the article, and what each costs.
        assertTrue(broke.contains("INV-05"), "check-then-act on the key must lose the duplicate race");
        assertTrue(broke.contains("INV-06"), "read-modify-write on a balance must lose an update");
        assertTrue(broke.contains("INV-09"), "a funds check before the write must let the account overdraw");
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
