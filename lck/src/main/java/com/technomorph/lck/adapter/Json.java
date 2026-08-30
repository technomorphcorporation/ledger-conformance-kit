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
        StringBuilder sb = new StringBuilder();
        for (int p = c + 1; p < json.length(); p++) {
            char ch = json.charAt(p);
            if (ch == '\\') { sb.append(json.charAt(++p)); continue; }
            if (ch == '"') break;
            sb.append(ch);
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

    private static int indexOfKey(String json, String key) { return json.indexOf('"' + key + '"'); }

    private static String trunc(String s) { return s.length() <= 200 ? s : s.substring(0, 200) + "..."; }

    private Json() {}
}
