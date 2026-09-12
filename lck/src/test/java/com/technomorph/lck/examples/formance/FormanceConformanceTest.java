package com.technomorph.lck.examples.formance;

import com.technomorph.lck.adapter.FormanceLedgerAdapter;
import com.technomorph.lck.core.Invariants;
import com.technomorph.lck.core.Invariants.Result;
import com.technomorph.lck.core.Invariants.Status;
import com.technomorph.lck.report.Reports;
import com.technomorph.lck.tck.AdapterTck;

import org.junit.jupiter.api.*;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The suite against Formance Ledger, over its real v2 HTTP API.
 *
 * <p>Every other example in this repository tests a ledger written here, where a failing
 * invariant was put there on purpose. This one tests software nobody here controls, which makes
 * it the first run whose result is not known before it starts.
 *
 * <p><b>This test deliberately does not assert which invariants hold.</b> Asserting that would
 * mean writing down a guess about someone else's ledger and calling a mismatch a regression. What
 * it asserts is the part that is ours to be right about:
 *
 * <ol>
 *   <li>the adapter passes the TCK, so any finding is about the ledger rather than the harness;</li>
 *   <li>no invariant ends in {@link Status#ERROR}, because an exception escaping the adapter is a
 *       defect in {@link FormanceLedgerAdapter}, not in Formance;</li>
 *   <li>no invariant ends in {@link Status#INFRA}, because a dropped connection to a container on
 *       the same host means the harness is wrong about the environment.</li>
 * </ol>
 *
 * <p>The outcome itself is written to {@code build/reports/lck/} and read by a person. Pinning it
 * is a decision to take once it is known and understood — not one to encode in advance, and not
 * one to take at all until the result has been put to the Formance maintainers under the
 * disclosure practice this project follows.
 *
 * <p>The image is pinned by digest rather than by tag. A finding that cannot say exactly which
 * build produced it is not reproducible, and {@code latest} moves.
 */
@Tag("docker")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FormanceConformanceTest {

    private static final long SEED = Long.getLong("lck.seed", 42L);

    /** ghcr.io/formancehq/ledger:latest as resolved on 12 September 2026 — server v2.3.22. */
    private static final String LEDGER_IMAGE = "ghcr.io/formancehq/ledger@sha256:"
            + "e3dc9097981474e0a30ff804fa1d0da353bb527a0b045411a0e3a150c8764c50";

    private static Network network;
    private static PostgreSQLContainer<?> postgres;
    private static GenericContainer<?> ledger;
    private static FormanceLedgerAdapter adapter;

    @BeforeAll
    static void start() {
        Assumptions.assumeTrue(dockerAvailable(),
                "Docker is not available; the Formance run is skipped, not failed");

        network = Network.newNetwork();
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withNetwork(network).withNetworkAliases("postgres")
                .withUsername("ledger").withPassword("ledger").withDatabaseName("ledger");
        postgres.start();

        ledger = new GenericContainer<>(DockerImageName.parse(LEDGER_IMAGE))
                .withNetwork(network)
                .withCommand("serve")
                .withEnv("POSTGRES_URI", "postgresql://ledger:ledger@postgres:5432/ledger?sslmode=disable")
                .withExposedPorts(3068)
                .waitingFor(Wait.forHttp("/_info").forPort(3068).forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        ledger.start();

        adapter = new FormanceLedgerAdapter(
                "http://" + ledger.getHost() + ":" + ledger.getMappedPort(3068));
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
        if (adapter != null) adapter.close();
        if (ledger != null) ledger.stop();
        if (postgres != null) postgres.stop();
        if (network != null) network.close();
    }

    @Test
    @Order(1)
    @DisplayName("the adapter passes the TCK, so findings below are about Formance and not about us")
    void adapterIsTrustworthy() throws Exception {
        List<AdapterTck.Finding> checks = AdapterTck.verify(adapter);

        assertTrue(AdapterTck.trustworthy(checks), () ->
                "the TCK failed, which means the adapter and the kit disagree about the contract. "
                        + "Every invariant result below would be evidence about this adapter rather "
                        + "than about Formance, and must not be reported as a finding:\n  "
                        + String.join("\n  ", checks.stream().filter(c -> !c.ok())
                        .map(c -> c.id() + " " + c.requirement() + ": " + c.detail()).toList()));
    }

    @Test
    @Order(2)
    @DisplayName("the suite runs to completion against a live Formance ledger")
    void suiteRuns() throws Exception {
        List<Result> results = Invariants.run(adapter, SEED);
        report(results);

        List<Result> errored = results.stream().filter(r -> r.status() == Status.ERROR).toList();
        assertTrue(errored.isEmpty(), () ->
                "an exception escaped the adapter. That is a defect in FormanceLedgerAdapter, not "
                        + "in Formance, and reporting it as a finding would be exactly the false "
                        + "positive this kit exists to avoid:\n  "
                        + errored.stream().map(r -> r.id() + ": " + r.detail()).toList());

        List<Result> infra = results.stream().filter(r -> r.status() == Status.INFRA).toList();
        assertTrue(infra.isEmpty(), () ->
                "the ledger became unreachable mid-run. Against a container on this host that is "
                        + "the harness being wrong about its environment, not a finding:\n  "
                        + infra.stream().map(r -> r.id() + ": " + r.detail()).toList());

        // Printed rather than asserted: the first run against a third-party ledger produces a
        // result, and a result is something to read and then take to its maintainers — not
        // something to have predicted in an assertion written beforehand.
        System.out.println("\n--- Formance v2.3.22, seed " + SEED + " ---");
        for (Result r : results)
            System.out.printf("  %-8s %-6s %s%n", r.id(), r.status(),
                    r.detail() == null ? "" : r.detail());
    }

    private static void report(List<Result> results) throws Exception {
        Path dir = Path.of("build", "reports", "lck");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("formance.html"), Reports.html(results, "formance"));
        Files.writeString(dir.resolve("formance.json"), Reports.json(results, "formance"));
    }
}
