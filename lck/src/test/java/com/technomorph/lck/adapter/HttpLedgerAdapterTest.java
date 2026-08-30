package com.technomorph.lck.adapter;

import com.sun.net.httpserver.HttpServer;
import com.technomorph.lck.spi.Capability;
import com.technomorph.lck.spi.Model.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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


    @Test
    @DisplayName("close releases the HTTP client, and the adapter works in try-with-resources")
    void closeReleasesTheClient() throws Exception {
        HttpLedgerAdapter outside;
        try (HttpLedgerAdapter http = serving(0, 200, "{\"subunits\":700}")) {
            assertEquals(700, http.balance("acct:a", "USD"));
            outside = http;
        }
        assertThrows(Exception.class, () -> outside.balance("acct:a", "USD"),
                "a closed client must not keep serving requests from a pool nobody owns");
    }

    // ------------------------------------------------------------ hostile identifiers

    /**
     * What an internal account key looks like when it is not the sanitised sort this suite
     * generates for itself. The quote is the one that mattered: it closed the JSON string
     * early and put every field after it in the wrong place.
     */
    private static final String HOSTILE_ACCOUNT = "acct:\"omnibus\"\\EMEA\tdesk-3";

    @Test
    @DisplayName("an account identifier containing quotes and backslashes produces valid JSON")
    void hostileIdentifiersAreEscapedInTheRequest() throws Exception {
        HttpLedgerAdapter http = capturing("{\"status\":\"APPLIED\",\"transactionId\":\"t1\"}");

        http.post(new Transaction(HOSTILE_ACCOUNT + "-key",
                List.of(Leg.debit(HOSTILE_ACCOUNT, 500), Leg.credit("acct:b", 500)),
                "txn\"1", true, Map.of()));

        String body = captured.get();
        assertNotNull(body, "the server received no request");
        assertBalancedStrings(body);
        for (int i = 0; i < body.length(); i++)
            assertTrue(body.charAt(i) >= 0x20,
                    () -> "raw control character in the request body: " + body);
        assertTrue(body.contains("acct:\\\"omnibus\\\""),
                () -> "the quote must be escaped, not passed through: " + body);
        assertTrue(body.contains("\\\\EMEA\\t"),
                () -> "backslash and tab must be escaped: " + body);
        assertTrue(body.contains("\"transactionId\":\"txn\\\"1\""),
                () -> "the transaction id is a client string too: " + body);
    }

    @Test
    @DisplayName("an escaped identifier survives the round trip byte for byte")
    void hostileIdentifiersSurviveTheResponse() throws Exception {
        String account = "acct:\\\"omnibus\\\"\\\\EMEA\\tdesk-3";   // as it appears on the wire
        HttpLedgerAdapter http = capturing("[{\"entryId\":\"e1\",\"transactionId\":\"t1\","
                + "\"accountId\":\"" + account + "\",\"type\":\"DEBIT\",\"amountSubunits\":500,"
                + "\"currency\":\"USD\",\"sequence\":1,\"accountSequence\":1,"
                + "\"prevHash\":\"GENESIS\",\"entryHash\":\"a\\" + "u0041b\"}]");

        List<JournalEntry> journal = http.journal();

        assertEquals(1, journal.size());
        assertEquals(HOSTILE_ACCOUNT, journal.get(0).accountId(),
                "reading must honour the escapes we write, or a value is corrupted on the way back");
        assertEquals("aAb", journal.get(0).entryHash(),
                "a \\uXXXX escape must be decoded, not spelled out as the letter u");
    }

    @Test
    @DisplayName("a null balance is refused with the sentence a decimal gets")
    void nullBalanceIsRefusedClearly() {
        HttpLedgerAdapter http = serving(0, 200, "{\"subunits\":null}");

        Exception e = assertThrows(Exception.class, () -> http.balance("acct:a", "USD"));
        assertTrue(e.getMessage().contains("minor units"),
                () -> "a NumberFormatException from an empty string tells the reader nothing: "
                        + e.getMessage());
    }

    // ------------------------------------------------------------------ plumbing

    private final AtomicReference<String> captured = new AtomicReference<>();

    /** Records the request body it was sent, and answers with {@code response}. */
    private HttpLedgerAdapter capturing(String response) {
        server.createContext("/", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        return new HttpLedgerAdapter("http://127.0.0.1:" + server.getAddress().getPort(),
                EnumSet.noneOf(Capability.class));
    }

    /** A quote that escaped its own field leaves an odd number of string delimiters behind. */
    private static void assertBalancedStrings(String json) {
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString && c == '\\') { i++; continue; }
            if (c == '"') inString = !inString;
        }
        assertFalse(inString, () -> "unterminated string literal in: " + json);
    }
}
