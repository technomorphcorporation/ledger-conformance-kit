package com.technomorph.lck.adapter;

import java.util.ArrayList;
import java.util.List;

/**
 * A deliberately tiny JSON reader for flat objects and arrays of flat objects.
 *
 * Adding Jackson here would put a large, frequently-CVE'd dependency into the artifact
 * a bank has to review, in exchange for parsing four response shapes we define ourselves.
 * If the wire format ever needs nesting, revisit — but the format is ours, so keep it flat.
 */
final class Json {

    static String str(String json, String key) {
        String v = strOr(json, key, null);
        if (v == null) throw new IllegalStateException("missing field '" + key + "' in: " + trunc(json));
        return v;
    }

    static String strOr(String json, String key, String dflt) {
        int i = indexOfKey(json, key);
        if (i < 0) return dflt;
        int c = json.indexOf(':', i) + 1;
        while (c < json.length() && Character.isWhitespace(json.charAt(c))) c++;
        if (c < json.length() && json.charAt(c) == 'n') return dflt;          // null
        if (c >= json.length() || json.charAt(c) != '"') return dflt;
        // Reading has to honour what quote() writes, or a value survives the request and is
        // corrupted on the way back: the old version appended the character after a backslash
        // verbatim, so \n arrived as the letter n and \u0041 as u0041.
        StringBuilder sb = new StringBuilder();
        for (int p = c + 1; p < json.length(); p++) {
            char ch = json.charAt(p);
            if (ch == '"') break;
            if (ch != '\\') { sb.append(ch); continue; }
            if (++p >= json.length()) break;
            char esc = json.charAt(p);
            switch (esc) {
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (p + 4 < json.length()) {
                        sb.append((char) Integer.parseInt(json.substring(p + 1, p + 5), 16));
                        p += 4;
                    }
                }
                default -> sb.append(esc);          // covers \" and \\ and \/
            }
        }
        return sb.toString();
    }

    static long num(String json, String key) {
        int i = indexOfKey(json, key);
        if (i < 0) throw new IllegalStateException("missing field '" + key + "' in: " + trunc(json));
        int c = json.indexOf(':', i) + 1;
        int e = c;
        while (e < json.length() && "-+0123456789. \t".indexOf(json.charAt(e)) >= 0) e++;
        String raw = json.substring(c, e).trim();
        if (raw.isEmpty()) throw new IllegalStateException(
                "field '" + key + "' came back empty or null — money must be an integer of minor units");
        if (raw.contains(".")) throw new IllegalStateException(
                "field '" + key + "' came back as " + raw + " — money must be an integer of minor units");
        return Long.parseLong(raw);
    }

    /** Split a top-level array into its object literals. */
    static List<String> objects(String jsonArray) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = -1;
        boolean inStr = false;
        for (int i = 0; i < jsonArray.length(); i++) {
            char c = jsonArray.charAt(i);
            if (inStr) { if (c == '\\') i++; else if (c == '"') inStr = false; continue; }
            if (c == '"') { inStr = true; continue; }
            if (c == '{') { if (depth++ == 0) start = i; }
            else if (c == '}') { if (--depth == 0 && start >= 0) out.add(jsonArray.substring(start, i + 1)); }
        }
        return out;
    }

    /**
     * A JSON string literal, quotes included, with everything that needs escaping escaped.
     *
     * <p>The request body used to be built by concatenation, so an account identifier containing
     * a quote or a backslash produced a malformed request — and the failure reached the client
     * as an unexplained ledger error rather than as a bug in this kit. Client account naming is
     * exactly the thing that varies, and the class javadoc invites firms to keep their internal
     * naming here.
     *
     * <p>Deliberately not shared with the escaper in {@code Reports}: that one also has to serve
     * XML and HTML, and a dozen lines duplicated across two packages is cheaper than a utility
     * module or a widened public surface.
     */
    static String quote(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 16).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static int indexOfKey(String json, String key) { return json.indexOf('"' + key + '"'); }

    private static String trunc(String s) { return s.length() <= 200 ? s : s.substring(0, 200) + "..."; }

    private Json() {}
}
