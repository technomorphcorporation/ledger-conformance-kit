package com.technomorph.lck.examples.tigerbeetle;

import com.tigerbeetle.Client;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * A single-replica TigerBeetle cluster for the duration of one test class.
 *
 * <p>TigerBeetle has no truncate and no drop: a data file is formatted once and then only ever
 * appended to. So "a scratch environment" here means a cluster that did not exist a moment ago,
 * which is a stronger guarantee than an emptied one and is the reason TCK-00 can be satisfied
 * honestly rather than by assertion.
 *
 * <p>Two flags are load-bearing. {@code --development} is required because Direct IO is
 * unavailable inside Docker on macOS and on CI runners, and without it {@code format} refuses to
 * run at all. {@code --cluster=0} is reserved for testing, which the server says out loud on
 * startup; that warning is correct and is exactly what this is.
 */
final class Tb {

    /**
     * Client and server move in lockstep — a mismatch fails at the protocol, not at compile time —
     * so this tag must equal the {@code tigerbeetle} version in {@code libs.versions.toml}, and
     * {@link #assertVersionsMatch()} fails the build if it ever does not.
     */
    static final String VERSION = "0.16.46";

    private static final int PORT = 3000;

    private final StringBuilder log = new StringBuilder();
    private GenericContainer<?> container;
    private Client client;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Fails if the client jar and the container tag have drifted apart.
     *
     * <p>The first version of this check read {@code getImplementationVersion()} off the client's
     * package and compared that. The tigerbeetle-java jar carries no {@code Implementation-Version}
     * in its manifest, so that value is always null and the check always passed — a guard that
     * looked like protection and was decoration, which is worse than not having one.
     *
     * <p>The version now comes from the Gradle version catalog, handed in by the {@code dockerTest}
     * task, so what is compared is the jar actually on the classpath against the tag actually being
     * started. An absent property means the build did not wire it and the check is off, which fails
     * rather than passes quietly.
     */
    static void assertVersionsMatch() {
        String catalog = System.getProperty("lck.tigerbeetle.version");
        if (catalog == null)
            throw new IllegalStateException("lck.tigerbeetle.version was not set, so the client "
                    + "and container versions are unchecked. Run this through the dockerTest task, "
                    + "which supplies it from the version catalog.");
        if (!catalog.equals(VERSION))
            throw new IllegalStateException("tigerbeetle-java is " + catalog + " in the version "
                    + "catalog but Tb.VERSION is " + VERSION + ". Client and server move in "
                    + "lockstep; update both together.");
    }

    /**
     * Relaxes seccomp for this container, which TigerBeetle needs and which is not optional.
     *
     * <p>TigerBeetle does its IO through {@code io_uring}, and Docker 25.0.0 and later block
     * {@code io_uring_setup}, {@code io_uring_enter} and {@code io_uring_register} in the default
     * seccomp profile. Confirmed rather than assumed: removing this line and running the isolated
     * job on CI produces, from the replica itself, {@code error(io): io_uring is not available}
     * followed by {@code error: PermissionDenied}. It passes on a Mac either way, because Docker
     * Desktop permits those syscalls — which is why this was green locally and red on CI.
     *
     * <p>Scope is one throwaway container in a test run: no ports beyond the mapped replica port,
     * a cluster id reserved for testing, and a data file that lives and dies with the container.
     * A profile allowing only the three syscalls would be tighter, but it means carrying Docker's
     * whole default profile in the tree to add three lines to it, and that copy would then rot
     * against the real default.
     */
    private static final java.util.List<String> SECCOMP = java.util.List.of("seccomp=unconfined");

    Client start() {
        // The binary sits at /tigerbeetle and is not on PATH, so the shell entrypoint needs the
        // absolute path. format then exec start, so the server is PID 1 of the container and a
        // stop signal reaches it rather than the shell.
        container = new GenericContainer<>(
                DockerImageName.parse("ghcr.io/tigerbeetle/tigerbeetle:" + VERSION))
                .withCreateContainerCmdModifier(c -> {
                    c.withEntrypoint("sh");
                    c.getHostConfig().withSecurityOpts(SECCOMP);
                })
                // Buffered rather than streamed. A startup failure otherwise surfaces as a
                // wait-strategy timeout with an empty stacktrace and no clue why -- but a replica
                // that cannot reach a peer retries in a tight loop, and printing every frame
                // buries the run in thousands of identical warnings. So it is kept and only shown
                // if starting fails.
                .withLogConsumer(f -> {
                    if (log.length() < 8_000) log.append(f.getUtf8String());
                })
                .withCommand("-c",
                        "/tigerbeetle format --development --cluster=0 --replica=0 "
                                + "--replica-count=1 /tmp/0.tigerbeetle && "
                                + "exec /tigerbeetle start --development "
                                + "--addresses=0.0.0.0:" + PORT + " /tmp/0.tigerbeetle")
                .withExposedPorts(PORT)
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
        try {
            container.start();
        } catch (RuntimeException e) {
            throw new IllegalStateException("the TigerBeetle replica did not start. Its own output "
                    + "follows, which is the only thing that explains why:\n" + log, e);
        }

        // TigerBeetle's address parser takes an IP literal, not a hostname, and Testcontainers
        // hands back "localhost" on most machines. Passing that through fails inside the JNI
        // layer as "Replica addresses format is invalid", which says nothing about hostnames.
        String ip;
        try {
            ip = java.net.InetAddress.getByName(container.getHost()).getHostAddress();
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException("could not resolve " + container.getHost(), e);
        }
        // The wait strategy only proves the port was listening once. A replica that is then killed
        // -- most often out of memory, since it opens a gigabyte-scale data file -- leaves the
        // client retrying ConnectionRefused forever, because it has no connect timeout. That turns
        // a dead container into a test that never finishes and never says why. Checking here costs
        // a moment and converts it into an error naming the cause.
        assertStillRunning();

        client = new Client(new byte[16],                       // cluster 0, as formatted above
                new String[]{ip + ":" + container.getMappedPort(PORT)});
        return client;
    }

    private void assertStillRunning() {
        var state = container.getCurrentContainerInfo().getState();
        if (Boolean.TRUE.equals(state.getRunning())) return;
        throw new IllegalStateException("the TigerBeetle replica started and then exited"
                + " (exit=" + state.getExitCodeLong()
                + (Boolean.TRUE.equals(state.getOOMKilled()) ? ", OOM-killed" : "")
                + "). A replica needs room for a gigabyte-scale data file, so this is usually "
                + "memory pressure from other containers on the same daemon. Its own output:\n"
                + log);
    }

    /** The live client, for a test that needs a second adapter over the same cluster. */
    Client client() { return client; }

    void close() {
        if (client != null) client.close();
        if (container != null) container.stop();
    }
}
