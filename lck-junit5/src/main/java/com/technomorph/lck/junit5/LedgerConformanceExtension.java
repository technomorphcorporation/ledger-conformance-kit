package com.technomorph.lck.junit5;

import com.technomorph.lck.core.Invariants;
import com.technomorph.lck.core.Invariants.Invariant;
import com.technomorph.lck.core.Invariants.Result;
import com.technomorph.lck.spi.LedgerAdapter;
import com.technomorph.lck.tck.AdapterTck;

import org.junit.jupiter.api.extension.*;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * Turns the suite into ordinary JUnit tests, one per invariant.
 *
 * <p>The adapter TCK runs first. If the adapter itself is wrong, every conformance test
 * is <em>aborted</em> rather than failed — a red scorecard produced by a broken adapter
 * is worse than no scorecard, because someone will act on it.
 *
 * <p><b>Nothing touches the ledger during discovery.</b> The test names come from the
 * registry, which is static; the adapter is not constructed and the TCK does not run until
 * the first invariant actually executes. An IDE listing the tests in this class, or a
 * {@code --dry-run}, therefore does not reset the environment the adapter points at — which
 * matters most in exactly the case where the adapter is pointed somewhere it should not be.
 * It also means selecting one invariant runs one invariant, not all fourteen.
 */
public final class LedgerConformanceExtension implements TestTemplateInvocationContextProvider {

    @Override
    public boolean supportsTestTemplate(ExtensionContext ctx) {
        return ctx.getTestClass().map(c -> c.isAnnotationPresent(LedgerConformance.class)).orElse(false);
    }

    @Override
    public java.util.stream.Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(
            ExtensionContext ctx) {
        LedgerConformance cfg = ctx.getRequiredTestClass().getAnnotation(LedgerConformance.class);
        Suite suite = new Suite(cfg);

        List<TestTemplateInvocationContext> out = new ArrayList<>(Invariants.REGISTRY.size());
        for (Invariant inv : Invariants.REGISTRY)
            out.add(named(inv.id() + " " + inv.title(), () -> suite.execute(inv)));
        return out.stream();
    }

    /**
     * The adapter and its TCK verdict, established once on first use and shared by every
     * invocation. Synchronized rather than lazily raced: JUnit may run these in parallel,
     * and two adapters against one ledger is its own kind of finding.
     */
    private static final class Suite {
        private final LedgerConformance cfg;
        private LedgerAdapter adapter;
        private String tckFailure;      // null once the TCK has run and passed

        Suite(LedgerConformance cfg) { this.cfg = cfg; }

        private synchronized void ensureVerified() {
            if (adapter != null) return;
            LedgerAdapter a = instantiate(cfg.adapter());
            List<AdapterTck.Finding> tck = AdapterTck.verify(a);
            if (!AdapterTck.trustworthy(tck))
                tckFailure = tck.stream().filter(f -> !f.ok())
                        .map(f -> f.id() + " " + f.requirement() + " — " + f.detail())
                        .reduce((x, y) -> x + "; " + y).orElse("unknown");
            adapter = a;
        }

        void execute(Invariant inv) {
            ensureVerified();
            if (tckFailure != null)
                abort("adapter is not conformant, results suppressed: " + tckFailure);
            assertHeld(Invariants.runOne(adapter, inv, cfg.seed()), cfg);
        }
    }

    private static void assertHeld(Result r, LedgerConformance cfg) {
        switch (r.status()) {
            case PASS -> { }
            case SKIP -> abort(r.detail());
            case FAIL, ERROR -> {
                if (severityRank(r.severity().name()) <= severityRank(cfg.failOn().name()))
                    fail(r.detail() + System.lineSeparator()
                            + "  in production: " + r.productionSymptom() + System.lineSeparator()
                            + "  reproduce: seed=" + r.seed());
                else abort(r.severity() + " below configured failOn=" + cfg.failOn() + ": " + r.detail());
            }
        }
    }

    private static int severityRank(String s) {
        return switch (s) { case "BLOCKER" -> 0; case "MAJOR" -> 1; default -> 2; };
    }

    private static LedgerAdapter instantiate(Class<? extends LedgerAdapter> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    type.getName() + " needs a public no-arg constructor to be used with @LedgerConformance", e);
        }
    }

    private static TestTemplateInvocationContext named(String name, Executable body) {
        return new TestTemplateInvocationContext() {
            @Override public String getDisplayName(int i) { return name; }
            @Override public List<Extension> getAdditionalExtensions() {
                return List.of((BeforeTestExecutionCallback) c -> body.run());
            }
        };
    }

    @FunctionalInterface private interface Executable { void run(); }
}
