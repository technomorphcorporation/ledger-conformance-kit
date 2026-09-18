package com.technomorph.lck.examples.postgres;

import com.technomorph.lck.core.Invariants;
import com.technomorph.lck.core.Invariants.Result;
import com.technomorph.lck.core.Invariants.Status;
import com.technomorph.lck.report.Reports;
import com.technomorph.lck.tck.AdapterTck;

import org.junit.jupiter.api.*;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The suite against the double-entry pattern the widely-copied PostgreSQL tutorials teach.
 *
 * <p>The subject is a pattern, not a project. {@link TutorialLedger} implements the common
 * denominator of the published schemas, and its javadoc records where each choice comes from.
 * Nobody is named as defective, because the finding is not about any one author — it is about what
 * a schema teaches and what it leaves out.
 *
 * <p><b>Nothing here asserts which invariants hold.</b> That was not known when this was written
 * and predicting it would have been the whole problem: a suite pointed at a pattern in order to
 * confirm a thesis is not measuring anything. What is asserted is what is ours to be right about
 * — the adapter passes the TCK, and nothing ends in ERROR or INFRA, both of which would make the
 * results evidence about this adapter rather than about the pattern.
 */
@Tag("docker")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TutorialPatternTest {

    private static final long SEED = Long.getLong("lck.seed", 42L);

    private static PostgreSQLContainer<?> container;
    private static Pg pg;
    private static TutorialLedger ledger;

    @BeforeAll
    static void start() throws Exception {
        Assumptions.assumeTrue(dockerAvailable(),
                "Docker is not available; the tutorial-pattern run is skipped, not failed");
        container = new PostgreSQLContainer<>("postgres:16-alpine");
        container.start();
        pg = new Pg(container.getJdbcUrl(), container.getUsername(), container.getPassword());
        ledger = new TutorialLedger(pg);
    }

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
    @DisplayName("the adapter passes the TCK, so the findings are about the pattern and not about us")
    void adapterIsTrustworthy() throws Exception {
        List<AdapterTck.Finding> checks = AdapterTck.verify(ledger);

        assertTrue(AdapterTck.trustworthy(checks), () ->
                "the TCK failed, so this adapter and the kit disagree about the contract. Every "
                        + "result below would be evidence about the adapter rather than about the "
                        + "pattern, and none of it could be published:\n  "
                        + String.join("\n  ", checks.stream().filter(c -> !c.ok())
                        .map(c -> c.id() + " " + c.requirement() + ": " + c.detail()).toList()));
    }

    @Test
    @Order(2)
    @DisplayName("the suite runs to completion against the tutorial pattern")
    void suiteRuns() throws Exception {
        List<Result> results = Invariants.run(ledger, SEED);
        report(results);

        List<Result> errored = results.stream().filter(r -> r.status() == Status.ERROR).toList();
        assertTrue(errored.isEmpty(), () ->
                "an exception escaped the adapter, which is a defect in TutorialLedger and not in "
                        + "the pattern it implements:\n  "
                        + errored.stream().map(r -> r.id() + ": " + r.detail()).toList());

        List<Result> infra = results.stream().filter(r -> r.status() == Status.INFRA).toList();
        assertTrue(infra.isEmpty(), () ->
                "the database became unreachable mid-run, which is the harness being wrong about "
                        + "its environment:\n  "
                        + infra.stream().map(r -> r.id() + ": " + r.detail()).toList());

        System.out.println("\n--- tutorial pattern (PostgreSQL 16, READ COMMITTED), seed "
                + SEED + " ---");
        for (Result r : results)
            System.out.printf("  %-8s %-6s %s%n", r.id(), r.status(),
                    r.detail() == null ? "" : r.detail());

        List<Result> broke = results.stream().filter(Result::broke).toList();
        System.out.println("\n  held " + results.stream().filter(r -> r.status() == Status.PASS).count()
                + " · broke " + broke.size()
                + " · not applicable " + results.stream().filter(r -> r.status() == Status.SKIP).count());
        if (!broke.isEmpty())
            System.out.println("  broke: " + broke.stream().map(Result::id).toList());
    }

    @Test
    @Order(3)
    @DisplayName("two schema constraints close every failure, and nothing else changes")
    void twoConstraintsCloseThem() throws Exception {
        ConstrainedTutorialLedger fixed = new ConstrainedTutorialLedger(pg);

        assertTrue(AdapterTck.trustworthy(AdapterTck.verify(fixed)),
                "the remedy has to be a conformant adapter too, or the comparison is worthless");

        List<Result> results = Invariants.run(fixed, SEED);
        Files.writeString(Path.of("build", "reports", "lck", "constrained.json"),
                Reports.json(results, "constrained-tutorial"));

        System.out.println("\n--- the same pattern, plus a unique index and a deferred "
                + "constraint trigger, seed " + SEED + " ---");
        for (Result r : results)
            System.out.printf("  %-8s %-6s %s%n", r.id(), r.status(),
                    r.detail() == null ? "" : r.detail());

        List<Result> broke = results.stream().filter(Result::broke).toList();
        assertTrue(broke.isEmpty(), () ->
                "the remedy is the claim this report rests on, so it has to hold: " + broke.stream()
                        .map(r -> r.id() + ": " + r.detail()).toList());

        // The four the tutorial pattern broke, named individually. A blanket "nothing broke" would
        // still pass if an invariant silently stopped running.
        for (String id : List.of("INV-01", "INV-04", "INV-05", "INV-11")) {
            Result r = results.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
            assertEquals(Status.PASS, r.status(), () ->
                    id + " is one of the four the unconstrained pattern breaks; the remedy must "
                            + "turn it green, not skip it: " + r.status() + " " + r.detail());
        }
    }

    private static void report(List<Result> results) throws Exception {
        Path dir = Path.of("build", "reports", "lck");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("tutorial.html"), Reports.html(results, "tutorial-postgres"));
        Files.writeString(dir.resolve("tutorial.json"), Reports.json(results, "tutorial-postgres"));
    }
}
