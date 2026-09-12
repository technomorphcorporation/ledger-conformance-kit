package com.technomorph.lck.adapter;

import com.technomorph.lck.spi.Capability;
import com.technomorph.lck.spi.LedgerAdapter;
import com.technomorph.lck.spi.Model.EntryType;
import com.technomorph.lck.spi.Model.JournalEntry;
import com.technomorph.lck.spi.Model.Leg;
import com.technomorph.lck.spi.Model.PostResult;
import com.technomorph.lck.spi.Model.Transaction;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An adapter for Formance Ledger ({@code github.com/formancehq/ledger}), speaking its v2 HTTP
 * API directly.
 *
 * <p>Unlike {@link HttpLedgerAdapter}, which asks the operator to mount four {@code /_lck/*}
 * endpoints inside their own service, this one talks to a ledger nobody here controls. That is
 * the point: a finding against software we cannot edit is worth more than one against software
 * we can, and it is the only way to test a third-party ledger at all.
 *
 * <p><b>Every mapping decision is listed below, because the cheapest way to dismiss a finding is
 * to attack the harness that produced it.</b> Where the kit's model and Formance's disagree,
 * the disagreement is recorded here rather than resolved silently.
 *
 * <table border="1">
 *   <caption>Model mapping</caption>
 *   <tr><th>Kit</th><th>Formance v2</th><th>Note</th></tr>
 *   <tr><td>{@code idempotencyKey}</td><td>{@code Idempotency-Key} header</td>
 *       <td>Replay returns the original response with {@code Idempotency-Hit: true}</td></tr>
 *   <tr><td>{@code PostStatus.DUPLICATE}</td><td>{@code Idempotency-Hit: true}</td>
 *       <td>Exact: the header exists to say precisely this</td></tr>
 *   <tr><td>{@code PostStatus.REJECTED}</td><td>{@code INSUFFICIENT_FUND}</td>
 *       <td>A business outcome, not an error — nothing was posted and nothing was lost</td></tr>
 *   <tr><td>{@code allowOverdraft}</td><td>{@code ?force=true}</td>
 *       <td>Documented as "disable balance checks when passing postings"</td></tr>
 *   <tr><td>{@code reset()}</td><td>{@code POST /v2/{ledger}}</td>
 *       <td>A <em>new</em> ledger each time; see below</td></tr>
 * </table>
 *
 * <h2>Four decisions that could reasonably have gone the other way</h2>
 *
 * <p><b>1. {@code reset()} creates a fresh ledger rather than emptying one.</b> The v2 API has no
 * delete endpoint, so this is the only option — but it is also the better one. A ledger created a
 * moment ago is empty by construction rather than by assertion, so TCK-00's refusal to run
 * against a non-empty environment passes on evidence. No data belonging to anyone else is ever
 * touched, which matters when the target is somebody else's software.
 *
 * <p><b>2. The {@code Idempotency-Key} header is used, and the {@code reference} field is
 * deliberately left unset.</b> Formance has two deduplication mechanisms and they do not mean the
 * same thing: the header replays the original response, while a reused {@code reference} raises
 * {@code CONFLICT}. Only the first is idempotency in the sense {@link
 * com.technomorph.lck.spi.Model.PostStatus#DUPLICATE} means. Testing both at once would produce a
 * result attributable to neither. Running the same suite against the {@code reference} mechanism
 * is a separate question worth its own run, not a variation to fold in here.
 *
 * <p><b>3. The asset is the bare currency code.</b> Formance amounts are integers in the asset's
 * smallest unit, which is already the kit's money model, so {@code USD} carries subunits with no
 * conversion anywhere. A {@code USD/2} scale suffix would change only how a human reading the
 * Formance UI sees the number, and would put a second currency table in the tree to drift against
 * the kit's own. Arithmetic beats presentation here.
 *
 * <p><b>4. Legs are paired into postings greedily, per currency.</b> The kit models a transaction
 * as independent debit and credit legs; Formance models it as directed {@code source → destination}
 * postings. For the ordinary two-leg transaction the two are the same thing and the mapping is
 * exact. For a multi-leg transaction there is more than one valid pairing, and this adapter walks
 * debits and credits in order, emitting {@code min(remaining)} each time. Every pairing preserves
 * the per-account totals, which is what the invariants assert on, so the choice cannot change a
 * finding — but it is a choice, and it is written down.
 *
 * <h2>Capabilities, deliberately under-declared</h2>
 *
 * <p>{@link Capability#OVERDRAFT_GUARD} is declared: balance checks are on by default and
 * {@code force} exists precisely to switch them off. {@link Capability#REPLAY} is declared: the
 * transaction list is complete, and it reaches the kit by a genuinely different path from
 * {@code balance()}, which reads account volumes — so INV-13 compares two independent answers
 * rather than one answer twice.
 *
 * <p>{@link Capability#HASH_CHAIN} is <b>not</b> declared. Formance does hash its log, but that
 * chain is not carried on the entries this adapter produces, and declaring a capability the
 * adapter cannot actually evidence would manufacture a failure. {@link Capability#COMPENSATION}
 * is not declared either, pending a reading of what INV-12 requires an adapter to do: Formance
 * reverts by booking a compensating transaction and never deletes, which looks like a match, but
 * "looks like" is not the standard. An undeclared capability is reported as not applicable and
 * never as a failure, so under-declaring costs a row of coverage; over-declaring costs the
 * credibility of every other row.
 */
public final class FormanceLedgerAdapter implements LedgerAdapter {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final URI base;
    private final String prefix;
    private final String bearer;                 // null for a self-hosted ledger in dev mode
    private final AtomicInteger generation = new AtomicInteger();
    private volatile String ledger;
    private volatile String ensured;

    public FormanceLedgerAdapter(String baseUrl) { this(baseUrl, "lck", null); }

    public FormanceLedgerAdapter(String baseUrl, String ledgerPrefix, String bearerToken) {
        this.base = URI.create(baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.prefix = ledgerPrefix;
        this.bearer = bearerToken;
        this.ledger = ledgerPrefix + "-0";
    }

    @Override public String name() { return "formance"; }

    @Override public Set<Capability> capabilities() {
        return EnumSet.of(Capability.OVERDRAFT_GUARD, Capability.REPLAY);
    }

    /** The ledger under test, which changes on every {@link #reset()}. Exposed for diagnostics. */
    public String currentLedger() { return ledger; }

    @Override public void reset() throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            String next = prefix + "-" + generation.incrementAndGet();
            Res r = send("POST", "/v2/" + enc(next), "{}");
            if (r.ok()) { ledger = next; ensured = next; return; }
            // Racing another run against the same server is the one case worth tolerating: the
            // ledger exists and is not ours to reuse, so take the next name rather than write
            // into somebody else's data.
            if (!"LEDGER_ALREADY_EXISTS".equals(errorCode(r.body)))
                throw new IllegalStateException(
                        "could not create ledger " + next + ": " + r.status + " " + trunc(r.body));
        }
        throw new IllegalStateException("no unused ledger name under prefix " + prefix);
    }

    /**
     * Creates the current ledger if it is not there yet.
     *
     * <p>Nothing guarantees {@link #reset()} runs first. TCK-01 writes, then resets, then checks
     * the write is gone — so the adapter has to cope with a post arriving before any reset, and
     * the only reason this was ever noticed is that the TCK exercises exactly that order.
     */
    private void ensureLedger() throws Exception {
        String l = ledger;
        if (l.equals(ensured)) return;
        synchronized (this) {
            if (l.equals(ensured)) return;
            Res r = send("POST", "/v2/" + enc(l), "{}");
            if (!r.ok() && !"LEDGER_ALREADY_EXISTS".equals(errorCode(r.body)))
                throw new IllegalStateException(
                        "could not create ledger " + l + ": " + r.status + " " + trunc(r.body));
            ensured = l;
        }
    }

    @Override public PostResult post(Transaction txn) throws Exception {
        Paired paired = pair(txn.legs());
        if (!paired.unpaired.isEmpty())
            // A Formance posting is single-asset and balanced by construction: source, destination,
            // one amount, one asset. A transaction whose legs do not balance within a currency has
            // no representation in that model at all, so there is nothing to send and nothing for
            // the ledger to decide. Refusing here is the only honest option, but the reason has to
            // say who refused — a reader must not take this for Formance having evaluated it and
            // said no. See the note on INV-11 in docs/formance.md.
            return PostResult.rejected(txn.transactionId(),
                    "not representable in Formance's model: postings are single-asset and balanced "
                            + "by construction, and these legs leave " + paired.unpaired
                            + " with no counterparty in the same currency. The adapter refused this; "
                            + "the ledger was not asked");

        ensureLedger();
        String path = "/v2/" + enc(ledger) + "/transactions"
                + (txn.allowOverdraft() ? "?force=true" : "");
        Res r = send("POST", path, body(paired.postings), "Idempotency-Key", txn.idempotencyKey());

        if (r.ok()) {
            String id = strIn(objectAt(r.body, "data"), "id");
            return "true".equalsIgnoreCase(r.header("Idempotency-Hit"))
                    ? PostResult.duplicate(id == null ? txn.transactionId() : id)
                    : PostResult.applied(id == null ? txn.transactionId() : id);
        }

        String code = errorCode(r.body);
        return switch (code == null ? "" : code) {
            // Nothing was written, so nothing was lost. Reporting this as a failure would be
            // asserting throughput rather than correctness, which fails a conservative ledger
            // for being right.
            case "INSUFFICIENT_FUND" ->
                    PostResult.rejected(txn.transactionId(), "INSUFFICIENT_FUND: " + errorMessage(r.body));
            // Only reachable if a future change starts sending `reference`; see the class javadoc.
            case "CONFLICT" -> PostResult.duplicate(txn.transactionId());
            default -> throw new IllegalStateException(
                    "POST transaction -> " + r.status + " " + code + ": " + trunc(r.body));
        };
    }

    @Override public long balance(String accountId, String currency) throws Exception {
        Res r = send("GET", "/v2/" + enc(ledger) + "/accounts/" + enc(accountId) + "?expand=volumes", null);

        // An account Formance has never seen does not exist, and asking for it is a 404. That is
        // a balance of zero, not a missing ledger — the kit reads balances before funding them.
        if (r.status == 404 || "NOT_FOUND".equals(errorCode(r.body))) return 0L;
        if (!r.ok()) throw new IllegalStateException(
                "GET account " + accountId + " -> " + r.status + " " + trunc(r.body));

        String volumes = objectAt(objectAt(r.body, "data"), "volumes");
        String forAsset = objectAt(volumes, currency);
        if (forAsset == null) return 0L;                    // never transacted in this currency
        Long bal = longIn(forAsset, "balance");
        return bal == null ? 0L : bal;
    }

    @Override public List<JournalEntry> journal() throws Exception {
        List<String> txns = new ArrayList<>();
        String path = "/v2/" + enc(ledger) + "/transactions?pageSize=100";
        // Paging to exhaustion is not optional: a short read understates the journal, and INV-13
        // would then report a ledger that cannot rebuild its own balances. A pagination bug here
        // would look exactly like the defect the invariant exists to find.
        while (path != null) {
            Res r = send("GET", path, null);
            // TCK-00 reads the journal before anything has written, to refuse a non-scratch
            // environment. At that point reset() has not run and the ledger does not exist yet —
            // which is the emptiest a ledger can be, not a failure to report.
            if (r.status == 404 || "LEDGER_NOT_FOUND".equals(errorCode(r.body))) return List.of();
            if (!r.ok()) throw new IllegalStateException(
                    "GET transactions -> " + r.status + " " + trunc(r.body));
            String cursor = objectAt(r.body, "cursor");
            txns.addAll(Json.objects(arrayAt(cursor, "data")));
            String next = strIn(cursor, "next");
            path = (next == null || next.isEmpty())
                    ? null
                    : "/v2/" + enc(ledger) + "/transactions?cursor=" + enc(next);
        }

        // Formance returns newest first; the kit's sequence numbers have to run the other way.
        txns.sort((a, b) -> Long.compare(idOf(a), idOf(b)));

        List<JournalEntry> out = new ArrayList<>();
        Map<String, Long> perAccount = new HashMap<>();
        long seq = 0;
        for (String t : txns) {
            String txnId = String.valueOf(idOf(t));
            int i = 0;
            for (String p : Json.objects(arrayAt(t, "postings"))) {
                long amount = longOr(p, "amount", 0L);
                String asset = strIn(p, "asset");
                String src = strIn(p, "source");
                String dst = strIn(p, "destination");
                // One posting is a debit and a credit. The kit counts entries, so it gets both.
                out.add(entry(txnId + ":" + i + ":d", txnId, src, EntryType.DEBIT, amount, asset,
                        ++seq, perAccount.merge(src + "|" + asset, 1L, Long::sum)));
                out.add(entry(txnId + ":" + i + ":c", txnId, dst, EntryType.CREDIT, amount, asset,
                        ++seq, perAccount.merge(dst + "|" + asset, 1L, Long::sum)));
                i++;
            }
        }
        return out;
    }

    @Override public void close() { http.close(); }

    // ------------------------------------------------------------------ request construction

    private String body(List<Posting> ps) {
        StringBuilder sb = new StringBuilder("{\"postings\":[");
        for (int i = 0; i < ps.size(); i++) {
            Posting p = ps.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"source\":").append(Json.quote(p.source))
              .append(",\"destination\":").append(Json.quote(p.destination))
              .append(",\"amount\":").append(p.amount)
              .append(",\"asset\":").append(Json.quote(p.asset)).append('}');
        }
        // `metadata` is required by the v2 schema even when there is nothing to say.
        return sb.append("],\"metadata\":{}}").toString();
    }

    record Posting(String source, String destination, long amount, String asset) { }

    /**
     * Pairs debit legs against credit legs of the same currency. See decision 4 in the class
     * javadoc: for the two-leg case this is exact, and for wider transactions every valid pairing
     * preserves the per-account totals the invariants assert on.
     */
    static List<Posting> postings(List<Leg> legs) { return pair(legs).postings; }

    /** Postings, plus whatever could not be paired — see {@link #post} for why that matters. */
    record Paired(List<Posting> postings, List<String> unpaired) { }

    static Paired pair(List<Leg> legs) {
        List<Posting> out = new ArrayList<>();
        List<String> unpaired = new ArrayList<>();
        for (String ccy : legs.stream().map(Leg::currency).distinct().toList()) {
            List<long[]> debits = new ArrayList<>();      // index into names, remaining amount
            List<long[]> credits = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (Leg l : legs) {
                if (!l.currency().equals(ccy)) continue;
                names.add(l.accountId());
                long[] slot = {names.size() - 1, l.amountSubunits()};
                (l.type() == EntryType.DEBIT ? debits : credits).add(slot);
            }
            int d = 0, c = 0;
            while (d < debits.size() && c < credits.size()) {
                long move = Math.min(debits.get(d)[1], credits.get(c)[1]);
                if (move > 0) out.add(new Posting(names.get((int) debits.get(d)[0]),
                        names.get((int) credits.get(c)[0]), move, ccy));
                debits.get(d)[1] -= move;
                credits.get(c)[1] -= move;
                if (debits.get(d)[1] == 0) d++;
                if (credits.get(c)[1] == 0) c++;
            }
            // Anything still holding an amount had no counterparty in its own currency. Sending
            // the paired remainder would move different money from the money the caller asked to
            // move, so the residue is carried out rather than dropped.
            for (int i = d; i < debits.size(); i++)
                if (debits.get(i)[1] > 0) unpaired.add("debit " + names.get((int) debits.get(i)[0])
                        + " " + debits.get(i)[1] + " " + ccy);
            for (int i = c; i < credits.size(); i++)
                if (credits.get(i)[1] > 0) unpaired.add("credit " + names.get((int) credits.get(i)[0])
                        + " " + credits.get(i)[1] + " " + ccy);
        }
        return new Paired(out, unpaired);
    }

    private static JournalEntry entry(String id, String txnId, String acct, EntryType type,
                                      long amount, String ccy, long seq, long acctSeq) {
        // prevHash/entryHash are null because Formance does not expose a per-entry chain, which is
        // why HASH_CHAIN is not declared. Inventing hashes here would satisfy INV-03 with a value
        // this adapter computed itself, which proves nothing about the ledger.
        return new JournalEntry(id, txnId, acct, type, amount, ccy, seq, acctSeq, null, null);
    }

    // ------------------------------------------------------------------ transport

    private record Res(int status, String body, HttpResponse<String> raw) {
        boolean ok() { return status / 100 == 2; }
        String header(String n) { return raw.headers().firstValue(n).orElse(null); }
    }

    private static final int ATTEMPTS = 3;

    private Res send(String method, String path, String body, String... headers) throws Exception {
        IOException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                Res r = sendOnce(method, path, body, headers);
                // 5xx is the ledger falling over or something in front of it: infrastructure, and
                // retryable. A 4xx is the kit and the API disagreeing about the contract, which is
                // a real result and must not be retried into looking intermittent.
                if (r.status / 100 == 5 && attempt < ATTEMPTS) { Thread.sleep(50L * attempt); continue; }
                if (r.status / 100 == 5) throw new HttpLedgerAdapter.TransportException(
                        method + " " + path + " -> " + r.status + " " + trunc(r.body), null);
                return r;
            } catch (IOException e) {
                last = e;
                if (attempt < ATTEMPTS) Thread.sleep(50L * attempt);
            }
        }
        throw new HttpLedgerAdapter.TransportException(method + " " + path + " failed " + ATTEMPTS
                + " times, last: " + last.getClass().getSimpleName() + ": " + last.getMessage(), last);
    }

    private Res sendOnce(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i + 1 < headers.length; i += 2)
            if (headers[i + 1] != null) b.header(headers[i], headers[i + 1]);
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body(), r);
    }

    // ------------------------------------------------------------------ minimal JSON scoping
    //
    // Json handles flat objects; a v2 response is nested, and a flat search for "balance" would
    // happily find one belonging to a different asset. These walk to the right object first.

    /** The object value of {@code key}, braces included, or null. */
    static String objectAt(String json, String key) { return scoped(json, key, '{', '}'); }

    /** The array value of {@code key}, brackets included, or {@code []}. */
    static String arrayAt(String json, String key) {
        String s = scoped(json, key, '[', ']');
        return s == null ? "[]" : s;
    }

    private static String scoped(String json, String key, char open, char close) {
        if (json == null) return null;
        int k = json.indexOf('"' + key + '"');
        if (k < 0) return null;
        int i = json.indexOf(open, k + key.length() + 2);
        if (i < 0) return null;
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int j = i; j < json.length(); j++) {
            char c = json.charAt(j);
            if (esc) { esc = false; continue; }
            if (c == '\\') { esc = true; continue; }
            if (c == '"') { inStr = !inStr; continue; }
            if (inStr) continue;
            if (c == open) depth++;
            else if (c == close && --depth == 0) return json.substring(i, j + 1);
        }
        return null;
    }

    private static String strIn(String json, String key) {
        return json == null ? null : Json.strOr(json, key, null);
    }

    private static Long longIn(String json, String key) {
        if (json == null || json.indexOf('"' + key + '"') < 0) return null;
        return Json.num(json, key);
    }

    private static long longOr(String json, String key, long dflt) {
        Long v = longIn(json, key);
        return v == null ? dflt : v;
    }

    private static long idOf(String txn) { return longOr(txn, "id", 0L); }

    private static String errorCode(String body)    { return strIn(body, "errorCode"); }
    private static String errorMessage(String body) {
        String m = strIn(body, "errorMessage");
        return m == null ? "" : m;
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static String trunc(String s) {
        return s == null ? "" : s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
