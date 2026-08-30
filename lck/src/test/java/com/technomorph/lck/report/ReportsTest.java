package com.technomorph.lck.report;

import com.technomorph.lck.core.Invariants;
import com.technomorph.lck.core.Invariants.Result;
import com.technomorph.lck.core.Invariants.Severity;
import com.technomorph.lck.core.Invariants.Status;
import com.technomorph.lck.examples.NaiveLedger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The reports are the product's output, and until now nothing checked that they parse.
 *
 * <p>The text they carry is not all ours: rejection reasons and exception messages come from
 * the ledger under test. A client whose error string contains a quote, a tab or a stray control
 * character would have produced a report their CI could not read — and the natural conclusion
 * is that the kit is broken, which is the correct conclusion.
 */
class ReportsTest {

    /** Everything an adapter might put in a message, and a few things it should not. */
    private static final String HOSTILE =
            "reason: \"conflict\" on acct\\path\ttab\nnewline\rbellformfeed — naïve 日本";

    private static List<Result> results() {
        return List.of(
                new Result("INV-01", "Every transaction is balanced", Severity.BLOCKER,
                        Status.FAIL, HOSTILE, "symptom with <angle> & ampersand", 42, 12),
                new Result("INV-03", "Hash chain is intact", Severity.MAJOR,
                        Status.SKIP, "adapter does not declare HASH_CHAIN", "symptom", 42, 0),
                new Result("INV-06", "No lost updates", Severity.BLOCKER,
                        Status.INFRA, "could not reach the ledger", "symptom", 42, 3),
                new Result("INV-14", "Per-account ordering", Severity.MINOR,
                        Status.PASS, "all good", "symptom", 42, 5));
    }

    @Test
    @DisplayName("the JUnit XML parses, whatever the ledger put in its error message")
    void junitXmlIsWellFormed() throws Exception {
        Document doc = parse(Reports.junitXml(results(), "ledger \"quoted\" & <odd>"));

        assertEquals("testsuite", doc.getDocumentElement().getTagName());
        assertEquals("4", doc.getDocumentElement().getAttribute("tests"));
        assertEquals("1", doc.getDocumentElement().getAttribute("failures"));
        assertEquals("0", doc.getDocumentElement().getAttribute("errors"));
        assertEquals("2", doc.getDocumentElement().getAttribute("skipped"),
                "SKIP and INFRA both render as skipped: neither is a failure of the ledger");
    }

    @Test
    @DisplayName("an unreachable invariant is skipped in CI, not failed")
    void infraDoesNotRedenTheBuild() throws Exception {
        String xml = Reports.junitXml(results(), "ledger");
        int infraAt = xml.indexOf("could not reach the ledger");

        assertTrue(infraAt > 0, "the reason must survive into the report");
        assertTrue(xml.lastIndexOf("<skipped", infraAt) > xml.lastIndexOf("<failure", infraAt),
                "an INFRA result must be emitted as <skipped>, or one dropped connection "
                        + "reddens the build and the team stops believing the report");
    }

    @Test
    @DisplayName("the JSON and SARIF carry no raw control characters")
    void machineReadableOutputIsEscaped() {
        for (String out : List.of(Reports.json(results(), "ledger \"q\" \\ x"),
                                  Reports.sarif(results(), "ledger \"q\" \\ x"))) {
            for (int i = 0; i < out.length(); i++)
                if (out.charAt(i) < 0x20 && out.charAt(i) != '\n')
                    fail("raw control character U+" + String.format("%04X", (int) out.charAt(i))
                            + " at offset " + i + " — this is not parseable JSON");
            assertBalancedStrings(out);
        }
    }

    @Test
    @DisplayName("the HTML report escapes what the ledger sent and fetches nothing")
    void htmlIsSelfContainedAndEscaped() {
        String html = Reports.html(results(), "ledger <script>alert(1)</script>");

        assertFalse(html.contains("<script>"), "adapter-supplied text must not become markup");
        assertTrue(html.contains("&lt;script&gt;"));
        assertFalse(html.contains("http://") || html.contains("https://"),
                "a report must render with no network access");
        assertTrue(html.contains("Unreachable"), "the reader needs the unreachable count");
    }

    @Test
    @DisplayName("a real run of the naive ledger produces four reports that all parse")
    void endToEndAgainstAFailingLedger() throws Exception {
        List<Result> rs = Invariants.run(new NaiveLedger(), 42);

        parse(Reports.junitXml(rs, "naive-ledger"));
        assertBalancedStrings(Reports.json(rs, "naive-ledger"));
        assertBalancedStrings(Reports.sarif(rs, "naive-ledger"));
        assertTrue(Reports.html(rs, "naive-ledger").contains("invariants broke"));
    }

    // ------------------------------------------------------------------ plumbing

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Walks the document counting string literals, honouring escapes. A quote that was not
     * escaped ends its string early and every brace after it lands in the wrong place, so an
     * odd count is the signature of exactly the bug this is looking for.
     */
    private static void assertBalancedStrings(String json) {
        boolean inString = false;
        int strings = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString && c == '\\') { i++; continue; }
            if (c == '"') { inString = !inString; if (!inString) strings++; }
        }
        assertFalse(inString, "unterminated string literal — a quote escaped its own field");
        assertTrue(strings > 0, "no string literals found at all");
    }
}
