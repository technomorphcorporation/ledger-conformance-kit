package com.technomorph.lck.cli;

import com.technomorph.lck.core.Baseline;
import com.technomorph.lck.core.Invariants;
import com.technomorph.lck.core.Invariants.Result;
import com.technomorph.lck.core.Invariants.Status;
import com.technomorph.lck.report.Reports;
import com.technomorph.lck.spi.Capability;
import com.technomorph.lck.spi.LedgerAdapter;
import com.technomorph.lck.tck.AdapterTck;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * CLI. Exit code is the number of BLOCKER-severity regressions, so it drops into any
 * pipeline without a wrapper script.
 */
public final class Main {

    private static final String USAGE = """
        Ledger Conformance Kit

          lck run   --adapter <class|http://host:port> [options]
          lck tck   --adapter <class|http://host:port>
          lck demo

        Options
          --seed <n>              fixed seed (default 42). Print it with every finding.
          --baseline <file>       fail only on failures absent from this file
          --write-baseline <file> record today's failures and exit 0
          --out <dir>             write html/json/sarif/junit reports (default build/reports/lck)
          --capabilities A,B      capabilities the ledger has, for the HTTP adapter. Nothing is
                                  assumed: undeclared means the invariant reports not applicable.
                                  One of HASH_CHAIN, OVERDRAFT_GUARD, COMPENSATION, REPLAY
          --quiet                 machine-readable output only

        Exit code = number of BLOCKER-severity regressions.
        """;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { System.out.println(USAGE); System.exit(0); }
        String cmd = args[0];
        Opts o = Opts.parse(args);

        switch (cmd) {
            case "demo" -> System.exit(demo(o));
            case "tck"  -> System.exit(tck(load(o), o));
            case "run"  -> System.exit(run(load(o), o));
            default     -> { System.out.println(USAGE); System.exit(2); }
        }
    }

    // ------------------------------------------------------------------ commands

    private static int demo(Opts o) throws Exception {
        int blockers = 0;
        for (String cls : new String[]{"com.technomorph.lck.examples.ReferenceLedger",
                                       "com.technomorph.lck.examples.NaiveLedger"}) {
            LedgerAdapter led = instantiate(cls);
            blockers += report(led, Invariants.run(led, o.seed), o);
        }
        return blockers;
    }

    private static int tck(LedgerAdapter led, Opts o) {
        List<AdapterTck.Finding> fs = AdapterTck.verify(led);
        System.out.printf("%n  adapter TCK — %s%n  %s%n", led.name(), "-".repeat(96));
        for (AdapterTck.Finding f : fs)
            System.out.printf("  %-8s %-5s %-46s %s%n", f.id(), f.ok() ? "OK" : "FAIL",
                    trunc(f.requirement(), 44), trunc(f.detail(), 60));
        boolean ok = AdapterTck.trustworthy(fs);
        System.out.printf("  %s%n  %s%n%n", "-".repeat(96),
                ok ? "adapter is conformant" : "adapter is NOT conformant — fix before trusting findings");
        return ok ? 0 : 1;
    }

    private static int run(LedgerAdapter led, Opts o) throws Exception {
        // Never report conformance findings produced by an adapter we do not trust.
        List<AdapterTck.Finding> tckFindings = AdapterTck.verify(led);
        if (!AdapterTck.trustworthy(tckFindings)) {
            System.out.println("\n  Adapter TCK failed. Conformance results suppressed:\n");
            tckFindings.stream().filter(f -> !f.ok())
                    .forEach(f -> System.out.printf("    %s  %s — %s%n",
                            f.id(), f.requirement(), f.detail()));
            System.out.println("\n  A red scorecard from a broken adapter is worse than no scorecard.\n");
            return 1;
        }

        List<Result> rs = Invariants.run(led, o.seed);

        if (o.writeBaseline != null) {
            Baseline.write(Path.of(o.writeBaseline), rs);
            System.out.printf("%n  baseline written to %s (%d failures accepted)%n%n",
                    o.writeBaseline, rs.stream().filter(Result::broke).count());
            report(led, rs, o);
            return 0;
        }

        int blockers = report(led, rs, o);

        if (o.baseline != null) {
            Baseline b = Baseline.read(Path.of(o.baseline));
            List<Result> regressions = b.regressions(rs);
            List<String> fixed = b.fixed(rs);
            if (!fixed.isEmpty())
                System.out.printf("  fixed since baseline: %s — remove these lines from %s%n",
                        String.join(", ", fixed), o.baseline);
            blockers = (int) regressions.stream()
                    .filter(r -> r.severity() == Invariants.Severity.BLOCKER).count();
            System.out.printf("  %d regression(s) against a baseline of %d%n%n",
                    regressions.size(), b.size());
        }
        return blockers;
    }

    // ------------------------------------------------------------------ output

    private static int report(LedgerAdapter led, List<Result> rs, Opts o) throws Exception {
        if (!o.quiet) {
            System.out.printf("%n  %s   seed=%d%n  %s%n", led.name(), o.seed, "-".repeat(104));
            for (Result r : rs)
                System.out.printf("  %-8s %-6s %-8s %-46s %s%n", r.id(), r.status(), r.severity(),
                        trunc(r.title(), 44), trunc(r.detail(), 58));
            long held = rs.stream().filter(r -> r.status() == Status.PASS).count();
            long broke = rs.stream().filter(Result::broke).count();
            long skip = rs.stream().filter(r -> r.status() == Status.SKIP).count();
            long infra = rs.stream().filter(r -> r.status() == Status.INFRA).count();
            System.out.printf("  %s%n  held %d · broke %d · not applicable %d%s%n",
                    "-".repeat(104), held, broke, skip,
                    infra == 0 ? "" : " · unreachable " + infra);
            if (skip > 0)
                System.out.printf("  %d invariant(s) not applicable — the adapter declares no "
                        + "matching capability. Add --capabilities to run them.%n", skip);
        }
        Path dir = Path.of(o.out);
        Files.createDirectories(dir);
        String slug = led.name().replaceAll("[^A-Za-z0-9._-]", "_").toLowerCase(Locale.ROOT);
        Files.writeString(dir.resolve(slug + ".html"), Reports.html(rs, led.name()));
        Files.writeString(dir.resolve(slug + ".json"), Reports.json(rs, led.name()));
        Files.writeString(dir.resolve(slug + ".sarif"), Reports.sarif(rs, led.name()));
        Files.writeString(dir.resolve(slug + "-junit.xml"), Reports.junitXml(rs, led.name()));
        if (!o.quiet) System.out.printf("  reports -> %s/%s.{html,json,sarif}%n", dir, slug);
        return Invariants.blockerFailures(rs);
    }

    // ------------------------------------------------------------------ plumbing

    private static LedgerAdapter load(Opts o) throws Exception {
        if (o.adapter == null) { System.out.println(USAGE); System.exit(2); }
        if (o.adapter.startsWith("http://") || o.adapter.startsWith("https://")) {
            Class<?> c = Class.forName("com.technomorph.lck.adapter.HttpLedgerAdapter");
            return (LedgerAdapter) c.getDeclaredConstructor(String.class, java.util.Set.class)
                    .newInstance(o.adapter, o.capabilities);
        }
        return instantiate(o.adapter);
    }

    private static LedgerAdapter instantiate(String cls) throws Exception {
        return (LedgerAdapter) Class.forName(cls).getDeclaredConstructor().newInstance();
    }

    private static String trunc(String s, int n) {
        return s == null ? "" : s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static final class Opts {
        String adapter, baseline, writeBaseline;
        String out = "build/reports/lck";
        long seed = 42;
        boolean quiet;
        // Opt in, never assume. Declaring a capability the ledger does not have turns
        // "not applicable" into a failure, and failing a ledger for lacking a feature is the
        // fastest way for a tool to be dismissed — see Capability's own javadoc.
        java.util.Set<Capability> capabilities = EnumSet.noneOf(Capability.class);

        static Opts parse(String[] a) {
            Opts o = new Opts();
            for (int i = 1; i < a.length; i++) {
                switch (a[i]) {
                    case "--adapter"        -> o.adapter = a[++i];
                    case "--seed"           -> o.seed = Long.parseLong(a[++i]);
                    case "--baseline"       -> o.baseline = a[++i];
                    case "--write-baseline" -> o.writeBaseline = a[++i];
                    case "--out"            -> o.out = a[++i];
                    case "--quiet"          -> o.quiet = true;
                    case "--capabilities"   -> {
                        List<Capability> cs = new ArrayList<>();
                        for (String s : a[++i].split(",")) cs.add(Capability.valueOf(s.strip()));
                        o.capabilities = cs.isEmpty() ? EnumSet.noneOf(Capability.class)
                                : EnumSet.copyOf(cs);
                    }
                    default -> { }
                }
            }
            return o;
        }
    }

    private Main() {}
}
