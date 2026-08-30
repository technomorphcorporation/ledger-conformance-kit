package com.technomorph.lck.adapter;

import com.technomorph.lck.spi.LedgerAdapter;
import com.technomorph.lck.spi.Capability;
import com.technomorph.lck.spi.Model.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * The universal adapter. Any ledger in any language becomes testable by exposing four
 * test-only endpoints against a scratch environment.
 *
 * <pre>
 *   POST /_lck/reset
 *   POST /_lck/post      {"idempotencyKey","legs":[{"accountId","type","amountSubunits","currency"}],
 *                         "allowOverdraft"}          -> {"status","transactionId","reason"}
 *   GET  /_lck/balance?account=&amp;currency=            -> {"subunits":12345}
 *   GET  /_lck/journal                                -> [{"entryId","transactionId","accountId",
 *                                                          "type","amountSubunits","currency",
 *                                                          "sequence","accountSequence",
 *                                                          "prevHash","entryHash"}]
 * </pre>
 *
 * <p>Roughly 80 lines to implement in any stack. Mount it behind a build-profile flag so
 * it cannot exist in production, and have it refuse to start against a non-scratch datasource.
 *
 * <p>Uses {@code java.net.http} and a hand-rolled minimal JSON reader so this module also
 * carries zero external dependencies. That is not stubbornness — it is what keeps the whole
 * artifact set reviewable at a regulated firm.
 */
public final class HttpLedgerAdapter implements LedgerAdapter {

    private final URI base;
    private final HttpClient http;
    private final Set<Capability> caps;

    public HttpLedgerAdapter(String baseUrl, Set<Capability> capabilities) {
        this.base = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.caps = capabilities;
        // HTTP/1.1 rather than negotiating: a burst opens hundreds of concurrent requests, and
        // an h2 upgrade would multiplex them onto one connection, serialising at the client and
        // making a correct ledger look slow. Concurrency is the measurement here, so keep it real.
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override public String name() { return "http:" + base; }
    @Override public Set<Capability> capabilities() { return caps; }

    @Override public void reset() throws Exception { send("POST", "/_lck/reset", "{}"); }

    @Override public PostResult post(Transaction txn) throws Exception {
        // Every string goes through Json.quote. Account identifiers are the client's, not ours,
        // and a quote or a backslash in one used to produce a malformed request that surfaced
        // as an unexplained ledger error.
        StringBuilder legs = new StringBuilder("[");
        for (int i = 0; i < txn.legs().size(); i++) {
            Leg l = txn.legs().get(i);
            if (i > 0) legs.append(',');
            legs.append("{\"accountId\":").append(Json.quote(l.accountId()))
                .append(",\"type\":").append(Json.quote(l.type().name()))
                .append(",\"amountSubunits\":").append(l.amountSubunits())
                .append(",\"currency\":").append(Json.quote(l.currency()))
                .append('}');
        }
        legs.append(']');
        String body = "{\"idempotencyKey\":" + Json.quote(txn.idempotencyKey())
                + ",\"transactionId\":" + Json.quote(txn.transactionId())
                + ",\"allowOverdraft\":" + txn.allowOverdraft()
                + ",\"legs\":" + legs + "}";

        String res = send("POST", "/_lck/post", body);
        String status = Json.str(res, "status");
        return switch (status) {
            case "APPLIED"   -> PostResult.applied(Json.str(res, "transactionId"));
            case "DUPLICATE" -> PostResult.duplicate(Json.str(res, "transactionId"));
            case "REJECTED"  -> PostResult.rejected(Json.str(res, "transactionId"), Json.str(res, "reason"));
            default -> throw new IllegalStateException("adapter returned unknown status: " + status);
        };
    }

    @Override public long balance(String accountId, String currency) throws Exception {
        String res = send("GET", "/_lck/balance?account=" + enc(accountId) + "&currency=" + enc(currency), null);
        return Json.num(res, "subunits");
    }

    @Override public List<JournalEntry> journal() throws Exception {
        String res = send("GET", "/_lck/journal", null);
        List<JournalEntry> out = new ArrayList<>();
        for (String o : Json.objects(res)) {
            out.add(new JournalEntry(
                    Json.str(o, "entryId"), Json.str(o, "transactionId"), Json.str(o, "accountId"),
                    EntryType.valueOf(Json.str(o, "type")), Json.num(o, "amountSubunits"),
                    Json.str(o, "currency"), Json.num(o, "sequence"), Json.num(o, "accountSequence"),
                    Json.strOr(o, "prevHash", ""), Json.strOr(o, "entryHash", "")));
        }
        return out;
    }

    /**
     * Every endpoint here is safe to repeat: reads are reads, {@code /_lck/reset} is idempotent,
     * and {@code /_lck/post} is idempotent on the key — which is the property the whole kit
     * exists to test, so relying on it here is fair. So a dropped connection is retried rather
     * than becoming a BLOCKER on someone's scorecard.
     */
    private static final int ATTEMPTS = 3;

    private String send(String method, String path, String body) throws Exception {
        IOException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                return sendOnce(method, path, body);
            } catch (IOException e) {
                last = e;
                if (attempt < ATTEMPTS) Thread.sleep(50L * attempt);   // linear is enough at this scale
            }
        }
        throw new TransportException(method + " " + path + " failed " + ATTEMPTS
                + " times, last: " + last.getClass().getSimpleName() + ": " + last.getMessage(), last);
    }

    private String sendOnce(String method, String path, String body) throws Exception {
        HttpRequest.BodyPublisher pub = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest req = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .method(method, pub).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        int code = r.statusCode();
        if (code / 100 == 2) return r.body();

        // 5xx is something in front of the ledger, or the ledger falling over — infrastructure
        // either way, and retryable. 4xx is the adapter and the kit disagreeing about the
        // contract, which is a real finding and must not be retried into looking intermittent.
        if (code / 100 == 5)
            throw new TransportException(method + " " + path + " -> " + code + " " + trunc(r.body()), null);
        throw new IllegalStateException(method + " " + path + " -> " + code + " " + trunc(r.body()));
    }

    private static String trunc(String s) {
        return s == null ? "" : s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }

    /**
     * Extends IOException on purpose: the runner classifies a failure as infrastructure by
     * looking for IOException in the cause chain, so an adapter written by a client gets the
     * same treatment for free by letting its own IOExceptions propagate.
     */
    static final class TransportException extends IOException {
        TransportException(String message, Throwable cause) { super(message, cause); }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
