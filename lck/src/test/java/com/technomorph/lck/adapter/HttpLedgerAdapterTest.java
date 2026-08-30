package com.technomorph.lck.adapter;

import com.sun.net.httpserver.HttpServer;
import com.technomorph.lck.spi.Capability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The universal adapter against a real socket.
 *
 * <p>{@code com.sun.net.httpserver} ships in the JDK, so testing the one component every
 * client's first run goes through costs no dependency — which was the reason it had no tests
 * at all until now.
 *
 * <p>What matters here is the distinction the suite draws downstream: a 5xx or a dropped
 * connection is infrastructure and gets retried, because every endpoint is idempotent; a 4xx
 * is the adapter and the kit disagreeing about the contract, and retrying that would turn a
 * real finding into something that looks intermittent.
 */
class HttpLedgerAdapterTest {

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** Answers with {@code status} for the first {@code failures} calls, then 200 and {@code body}. */
    private HttpLedgerAdapter serving(int failures, int status, String body) {
        server.createContext("/", exchange -> {
            int n = requests.incrementAndGet();
            byte[] out = (n <= failures ? "upstream unavailable" : body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(n <= failures ? status : 200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        return new HttpLedgerAdapter("http://127.0.0.1:" + server.getAddress().getPort(),
                EnumSet.noneOf(Capability.class));
    }

    @Test
    @DisplayName("a transient 5xx is retried, not reported")
    void transientServerErrorIsRetried() throws Exception {
        HttpLedgerAdapter http = serving(2, 503, "{\"subunits\":12345}");

        assertEquals(12_345, http.balance("acct:a", "USD"));
        assertEquals(3, requests.get(), "two failures then a success is three requests");
    }

    @Test
    @DisplayName("a persistent 5xx surfaces as an IOException, which the runner reads as INFRA")
    void persistentServerErrorIsTransport() {
        HttpLedgerAdapter http = serving(Integer.MAX_VALUE, 502, "");

        IOException e = assertThrows(IOException.class, () -> http.balance("acct:a", "USD"),
                "the runner classifies infrastructure by IOException in the cause chain, so a "
                        + "5xx must arrive as one or it becomes a BLOCKER against the ledger");
        assertTrue(e.getMessage().contains("502"), e::getMessage);
        assertEquals(3, requests.get(), "it should give up after the configured attempts");
    }

    @Test
    @DisplayName("a 4xx is a contract disagreement: reported immediately, never retried")
    void clientErrorIsNotRetried() {
        HttpLedgerAdapter http = serving(Integer.MAX_VALUE, 404, "");

        Exception e = assertThrows(Exception.class, () -> http.balance("acct:a", "USD"));
        assertFalse(e instanceof IOException,
                "a 404 is the adapter not implementing the contract — a finding, not infrastructure");
        assertEquals(1, requests.get(),
                "retrying a 4xx would make a permanent contract error look intermittent");
    }

    @Test
    @DisplayName("money that comes back with a decimal point is refused, not rounded")
    void decimalAmountsAreRefused() {
        HttpLedgerAdapter http = serving(0, 200, "{\"subunits\":123.45}");

        Exception e = assertThrows(Exception.class, () -> http.balance("acct:a", "USD"));
        assertTrue(e.getMessage().contains("minor units"), e::getMessage);
    }
}
