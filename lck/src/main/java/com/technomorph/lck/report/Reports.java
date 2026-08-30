package com.technomorph.lck.report;

import com.technomorph.lck.core.Invariants.Result;
import com.technomorph.lck.core.Invariants.Severity;
import com.technomorph.lck.core.Invariants.Status;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Four output formats, each aimed at a different reader.
 *
 * <ul>
 *   <li><b>JUnit XML</b> — every CI system already renders it, with per-test history</li>
 *   <li><b>SARIF</b> — how security teams already ingest findings (GitHub code scanning)</li>
 *   <li><b>JSON</b> — their dashboards, and the longitudinal corpus</li>
 *   <li><b>HTML</b> — a trial balance, for the people who fund the fix</li>
 * </ul>
 *
 * Hand-rolled emitters, no dependencies. See lck-spi's zero-dependency rule for why.
 */
public final class Reports {

    // ------------------------------------------------------------------ HTML

    public static String html(List<Result> rs, String ledgerName) {
        long held = rs.stream().filter(r -> r.status() == Status.PASS).count();
        long broke = rs.stream().filter(Result::broke).count();
        long skipped = rs.stream().filter(r -> r.status() == Status.SKIP).count();
        long blockers = rs.stream().filter(r -> r.broke() && r.severity() == Severity.BLOCKER).count();

        String verdict = broke == 0
                ? "Clean. " + held + " of " + (held + broke) + " invariants held."
                : broke + " of " + (held + broke) + " invariants broke"
                  + (blockers > 0 ? ", " + blockers + " of them silently wrong under load." : ".");

        StringBuilder rows = new StringBuilder();
        for (Result r : rs) {
            rows.append("<tr class=\"").append(r.status() == Status.PASS ? "pass" : "").append("\">")
                .append("<td class=\"id\">").append(esc(r.id())).append("</td><td>")
                .append("<div class=\"title\">").append(esc(r.title())).append("</div>")
                .append("<div class=\"detail\">").append(esc(r.detail())).append("</div>")
                .append("<div class=\"symptom\">").append(esc(r.productionSymptom())).append("</div>")
                .append("</td><td class=\"c-sev\"><span class=\"sev ").append(r.severity())
                .append("\">").append(r.severity()).append("</span></td>")
                .append("<td><span class=\"flag ").append(r.status()).append("\">")
                .append(r.status()).append("</span></td></tr>");
        }

        long seed = rs.isEmpty() ? 0 : rs.get(0).seed();
        return """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Ledger conformance — %s</title>
            <style>%s</style></head><body><div class="sheet">
            <header>
              <div class="eyebrow">Ledger Conformance Kit &middot; run report</div>
              <h1>%s</h1>
              <p class="sub">Executable invariants that any system moving money is expected to hold.
                 Each one is a check that has failed in a real production ledger.</p>
              <div class="meta"><span><b>Run</b> %s</span><span><b>Seed</b> %d</span>
                <span><b>Invariants</b> %d</span><span><b>Executed</b> %d</span></div>
            </header>
            <div class="tb">
              <div class="tb-label">Closing position</div>
              <div class="tb-rows">
                <div class="tb-row"><span>Held</span><span>%d</span></div>
                <div class="tb-row"><span>Broke</span><span>%d</span></div>
                <div class="tb-row"><span>Not applicable</span><span>%d</span></div>
                <div class="tb-row tb-total"><span>Invariants</span><span>%d</span></div>
              </div>
              <div class="verdict %s">%s</div>
            </div>
            <table><thead><tr><th>Ref</th><th>Invariant &amp; finding</th>
              <th class="c-sev">Severity</th><th>Result</th></tr></thead>
            <tbody>%s</tbody></table>
            <footer>A failing invariant is not a code-review opinion. It is a reproducible run
              against your own adapter, and it prints the exact discrepancy in currency subunits.
              Reproduce with <code>--seed %d</code>.</footer>
            </div></body></html>
            """.formatted(esc(ledgerName), CSS, esc(ledgerName),
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")),
                seed, rs.size(), held + broke, held, broke, skipped, rs.size(),
                broke == 0 ? "good" : "bad", esc(verdict), rows, seed);
    }

    // ------------------------------------------------------------- JUnit XML

    public static String junitXml(List<Result> rs, String ledgerName) {
        long failures = rs.stream().filter(r -> r.status() == Status.FAIL).count();
        long errors = rs.stream().filter(r -> r.status() == Status.ERROR).count();
        long skipped = rs.stream().filter(r -> r.status() == Status.SKIP).count();
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<testsuite name=\"ledger-conformance\" tests=\"").append(rs.size())
          .append("\" failures=\"").append(failures).append("\" errors=\"").append(errors)
          .append("\" skipped=\"").append(skipped).append("\">\n");
        for (Result r : rs) {
            sb.append("  <testcase classname=\"").append(esc(ledgerName)).append("\" name=\"")
              .append(esc(r.id() + " " + r.title())).append("\" time=\"")
              .append(r.millis() / 1000.0).append("\">");
            switch (r.status()) {
                case FAIL -> sb.append("\n    <failure message=\"").append(esc(r.detail()))
                        .append("\">").append(esc(r.productionSymptom()))
                        .append("\n\nReproduce: --seed ").append(r.seed()).append("</failure>\n  ");
                case ERROR -> sb.append("\n    <error message=\"").append(esc(r.detail()))
                        .append("\"/>\n  ");
                case SKIP -> sb.append("\n    <skipped message=\"").append(esc(r.detail()))
                        .append("\"/>\n  ");
                case PASS -> { }
            }
            sb.append("</testcase>\n");
        }
        return sb.append("</testsuite>\n").toString();
    }

    // ----------------------------------------------------------------- SARIF

    public static String sarif(List<Result> rs, String ledgerName) {
        StringBuilder rules = new StringBuilder();
        StringBuilder results = new StringBuilder();
        boolean firstRule = true, firstResult = true;
        for (Result r : rs) {
            if (!firstRule) rules.append(',');
            firstRule = false;
            rules.append("""
                {"id":"%s","name":"%s","shortDescription":{"text":"%s"},
                 "fullDescription":{"text":"%s"},
                 "defaultConfiguration":{"level":"%s"}}"""
                .formatted(r.id(), r.id(), jesc(r.title()), jesc(r.productionSymptom()),
                        r.severity() == Severity.BLOCKER ? "error"
                                : r.severity() == Severity.MAJOR ? "warning" : "note"));
            if (!r.broke()) continue;
            if (!firstResult) results.append(',');
            firstResult = false;
            results.append("""
                {"ruleId":"%s","level":"%s","message":{"text":"%s"},
                 "locations":[{"physicalLocation":{"artifactLocation":{"uri":"%s"}}}]}"""
                .formatted(r.id(), r.severity() == Severity.BLOCKER ? "error" : "warning",
                        jesc(r.detail() + " | in production: " + r.productionSymptom()
                                + " | reproduce with --seed " + r.seed()),
                        jesc(ledgerName)));
        }
        return """
            {"$schema":"https://json.schemastore.org/sarif-2.1.0.json","version":"2.1.0",
             "runs":[{"tool":{"driver":{"name":"Ledger Conformance Kit",
              "informationUri":"https://github.com/technomorphcorporation/ledger-conformance-kit",
              "rules":[%s]}},"results":[%s]}]}""".formatted(rules, results);
    }

    // ------------------------------------------------------------------ JSON

    public static String json(List<Result> rs, String ledgerName) {
        StringBuilder sb = new StringBuilder("{\"ledger\":\"").append(jesc(ledgerName))
                .append("\",\"seed\":").append(rs.isEmpty() ? 0 : rs.get(0).seed())
                .append(",\"results\":[");
        for (int i = 0; i < rs.size(); i++) {
            Result r = rs.get(i);
            if (i > 0) sb.append(',');
            sb.append("""
                {"id":"%s","title":"%s","severity":"%s","status":"%s",
                 "detail":"%s","productionSymptom":"%s","millis":%d}"""
                .formatted(r.id(), jesc(r.title()), r.severity(), r.status(),
                        jesc(r.detail()), jesc(r.productionSymptom()), r.millis()));
        }
        return sb.append("]}").toString();
    }

    // ------------------------------------------------------------------ util

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String jesc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    private static final String CSS = """
        /* System stacks only. A report is opened on a reviewer's machine inside a bank;
           a webfont link would make that an outbound request to a third party, which is
           the one thing COMPLIANCE.md promises this tool never does. Enforced by the
           remote-asset check in complianceCheck. */
        :root{--paper:#F2F6F0;--bar:#E1EBDE;--ink:#16221C;--soft:#5C6B60;
          --rule:#B9CCB6;--stamp:#A32A1F;--ok:#2C6E49;
          --mono:ui-monospace,SFMono-Regular,"SF Mono",Menlo,Consolas,"Liberation Mono",monospace;
          --sans:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
          --serif:Spectral,Georgia,"Times New Roman",serif}
        *{box-sizing:border-box}
        body{margin:0;background:#DCE4D9;color:var(--ink);font-family:var(--sans);font-size:14px;line-height:1.5;padding:28px 16px}
        .sheet{max-width:1040px;margin:0 auto;background:var(--paper);border:1px solid var(--rule);
          box-shadow:0 1px 0 #fff inset,0 8px 30px rgba(20,34,27,.13)}
        header{padding:30px 34px 22px;border-bottom:3px double var(--rule)}
        .eyebrow{font-family:var(--mono);font-size:10.5px;letter-spacing:.16em;
          text-transform:uppercase;color:var(--soft)}
        h1{font-family:var(--serif);font-weight:600;font-size:30px;
          letter-spacing:-.015em;margin:9px 0 6px;line-height:1.15}
        .sub{color:var(--soft);font-size:13.5px;max-width:62ch;margin:0}
        .meta{font-family:var(--mono);font-size:11px;color:var(--soft);
          margin-top:16px;display:flex;gap:22px;flex-wrap:wrap}
        .meta b{color:var(--ink);font-weight:600}
        .tb{padding:22px 34px;border-bottom:1px solid var(--rule);background:#fff}
        .tb-label{font-family:var(--mono);font-size:10.5px;letter-spacing:.14em;
          text-transform:uppercase;color:var(--soft);margin-bottom:10px}
        .tb-rows{font-family:var(--mono);font-size:13px;max-width:430px}
        .tb-row{display:flex;justify-content:space-between;padding:3px 0}
        .tb-row span:last-child{font-variant-numeric:tabular-nums}
        .tb-total{border-top:1px solid var(--ink);border-bottom:3px double var(--ink);
          margin-top:6px;padding:5px 0;font-weight:600}
        .verdict{margin-top:16px;font-family:var(--serif);font-size:19px;font-weight:600}
        .verdict.bad{color:var(--stamp)}.verdict.good{color:var(--ok)}
        table{width:100%;border-collapse:collapse}
        tbody tr:nth-child(4n+1),tbody tr:nth-child(4n+2){background:var(--bar)}
        th{font-family:var(--mono);font-size:10px;letter-spacing:.13em;
          text-transform:uppercase;color:var(--soft);text-align:left;font-weight:600;
          padding:12px 10px;border-bottom:1px solid var(--rule)}
        td{padding:11px 10px;vertical-align:top;border-bottom:1px solid rgba(185,204,182,.55)}
        td:first-child,th:first-child{padding-left:34px}
        td:last-child,th:last-child{padding-right:34px}
        .id{font-family:var(--mono);font-size:11.5px;color:var(--soft);white-space:nowrap}
        .title{font-weight:500}
        .detail{font-family:var(--mono);font-size:11.5px;color:var(--soft);
          margin-top:4px;line-height:1.45}
        .symptom{font-size:12.5px;color:var(--stamp);margin-top:6px;max-width:52ch}
        tr.pass .symptom{display:none}
        .flag{font-family:var(--mono);font-size:10px;letter-spacing:.1em;
          font-weight:600;padding:3px 7px;white-space:nowrap;border:1px solid currentColor}
        .flag.PASS{color:var(--ok)}.flag.FAIL,.flag.ERROR{color:var(--stamp);background:rgba(163,42,31,.07)}
        .flag.SKIP{color:var(--soft)}
        .sev{font-family:var(--mono);font-size:10px;color:var(--soft)}
        .sev.BLOCKER{color:var(--stamp);font-weight:600}
        footer{padding:22px 34px 30px;font-size:12px;color:var(--soft);border-top:3px double var(--rule)}
        @media (max-width:720px){td:first-child,th:first-child{padding-left:16px}
          td:last-child,th:last-child{padding-right:16px}
          header,.tb,footer{padding-left:18px;padding-right:18px}h1{font-size:24px}
          .c-sev{display:none}}
        @media print{body{background:#fff;padding:0}.sheet{box-shadow:none;border:none}
          tr{break-inside:avoid}}
        """;

    private Reports() {}
}
