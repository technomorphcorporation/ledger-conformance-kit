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

    Client start() {
        // The binary sits at /tigerbeetle and is not on PATH, so the shell entrypoint needs the
        // absolute path. format then exec start, so the server is PID 1 of the container and a
        // stop signal reaches it rather than the shell.
        container = new GenericContainer<>(
                DockerImageName.parse("ghcr.io/tigerbeetle/tigerbeetle:" + VERSION))
                .withCreateContainerCmdModifier(c -> c.withEntrypoint("sh"))
                .withCommand("-c",
                        "/tigerbeetle format --development --cluster=0 --replica=0 "
                                + "--replica-count=1 /tmp/0.tigerbeetle && "
                                + "exec /tigerbeetle start --development "
                                + "--addresses=0.0.0.0:" + PORT + " /tmp/0.tigerbeetle")
                .withExposedPorts(PORT)
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
        container.start();

        // TigerBeetle's address parser takes an IP literal, not a hostname, and Testcontainers
        // hands back "localhost" on most machines. Passing that through fails inside the JNI
        // layer as "Replica addresses format is invalid", which says nothing about hostnames.
        String ip;
        try {
            ip = java.net.InetAddress.getByName(container.getHost()).getHostAddress();
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException("could not resolve " + container.getHost(), e);
        }
        client = new Client(new byte[16],                       // cluster 0, as formatted above
                new String[]{ip + ":" + container.getMappedPort(PORT)});
        return client;
    }

    /** The live client, for a test that needs a second adapter over the same cluster. */
    Client client() { return client; }

    void close() {
        if (client != null) client.close();
        if (container != null) container.stop();
    }
}
