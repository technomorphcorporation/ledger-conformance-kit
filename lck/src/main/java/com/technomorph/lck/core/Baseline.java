package com.technomorph.lck.core;

import com.technomorph.lck.core.Invariants.Result;
import com.technomorph.lck.core.Invariants.Status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Ratchet mode.
 *
 * <p>A team with seven existing blockers cannot switch on a gate that reddens the build
 * on day one — they will disable it by Friday, and then the gate is worth nothing. The
 * baseline records today's failures so CI fails only on <em>new</em> ones. Stop the
 * bleeding immediately; shrink the baseline on a schedule.
 *
 * <p>Format is deliberately a flat, sorted text file: it must be reviewable in a pull
 * request, and a diff should read as "we fixed INV-06" without any tooling.
 */
public final class Baseline {

    private final Set<String> accepted;

    private Baseline(Set<String> accepted) { this.accepted = accepted; }

    public static Baseline empty() { return new Baseline(Set.of()); }

    public static Baseline read(Path p) throws IOException {
        if (!Files.exists(p)) return empty();
        Set<String> ids = new TreeSet<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String s = line.strip();
            if (!s.isEmpty() && !s.startsWith("#")) ids.add(s.split("\\s+")[0]);
        }
        return new Baseline(ids);
    }

    public static void write(Path p, List<Result> results) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# Ledger Conformance Kit baseline");
        lines.add("# Failures accepted for now. CI fails only on invariants NOT listed here.");
        lines.add("# Delete a line as you fix it. An empty file is the goal.");
        results.stream().filter(Result::broke).sorted(Comparator.comparing(Result::id))
               .forEach(r -> lines.add(r.id() + "   # " + r.severity() + " — " + r.title()));
        Files.write(p, lines, StandardCharsets.UTF_8);
    }

    /** Failures not present in the baseline. These are the ones that should fail the build. */
    public List<Result> regressions(List<Result> results) {
        return results.stream().filter(Result::broke)
                .filter(r -> !accepted.contains(r.id())).toList();
    }

    /** Baselined failures that now pass — the file can be shrunk. */
    public List<String> fixed(List<Result> results) {
        Set<String> passing = new HashSet<>();
        for (Result r : results) if (r.status() == Status.PASS) passing.add(r.id());
        return accepted.stream().filter(passing::contains).sorted().toList();
    }

    public boolean isEmpty() { return accepted.isEmpty(); }
    public int size() { return accepted.size(); }
}
