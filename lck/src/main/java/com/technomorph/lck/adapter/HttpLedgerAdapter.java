package com.technomorph.lck.adapter;

import com.technomorph.lck.spi.LedgerAdapter;
import com.technomorph.lck.spi.Capability;
import com.technomorph.lck.spi.Model.*;

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
        StringBuilder legs = new StringBuilder("[");
        for (int i = 0; i < txn.legs().size(); i++) {
            Leg l = txn.legs().get(i);
            if (i > 0) legs.append(',');
            legs.append("{\"accountId\":\"").append(l.accountId())
                .append("\",\"type\":\"").append(l.type())
                .append("\",\"amountSubunits\":").append(l.amountSubunits())
                .append(",\"currency\":\"").append(l.currency()).append("\"}");
        }
        legs.append(']');
        String body = "{\"idempotencyKey\":\"" + txn.idempotencyKey()
                + "\",\"transactionId\":\"" + txn.transactionId()
                + "\",\"allowOverdraft\":" + txn.allowOverdraft()
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

    private String send(String method, String path, String body) throws Exception {
        HttpRequest.BodyPublisher pub = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest req = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .method(method, pub).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2)
            throw new IllegalStateException(method + " " + path + " -> " + r.statusCode() + " " + r.body());
        return r.body();
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
