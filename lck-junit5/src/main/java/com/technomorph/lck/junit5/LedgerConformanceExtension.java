package com.technomorph.lck.junit5;

import com.technomorph.lck.core.Invariants;
import com.technomorph.lck.core.Invariants.Result;
import com.technomorph.lck.spi.LedgerAdapter;
import com.technomorph.lck.tck.AdapterTck;
import org.junit.jupiter.api.DynamicTest;
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
        LedgerAdapter adapter = instantiate(cfg.adapter());

        List<AdapterTck.Finding> tck = AdapterTck.verify(adapter);
        List<Result> results = AdapterTck.trustworthy(tck)
                ? Invariants.run(adapter, cfg.seed())
                : List.of();

        List<TestTemplateInvocationContext> out = new ArrayList<>();
        if (!AdapterTck.trustworthy(tck)) {
            String why = tck.stream().filter(f -> !f.ok())
                    .map(f -> f.id() + " " + f.requirement() + " — " + f.detail())
                    .reduce((a, b) -> a + "; " + b).orElse("unknown");
            out.add(named("adapter TCK", () -> abort("adapter is not conformant, results suppressed: " + why)));
            return out.stream();
        }
        for (Result r : results) out.add(named(r.id() + " " + r.title(), () -> assertHeld(r, cfg)));
        return out.stream();
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
