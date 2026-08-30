package com.technomorph.lck.core;

import com.technomorph.lck.core.Invariants.Result;
import com.technomorph.lck.core.Invariants.Status;
import com.technomorph.lck.examples.ReferenceLedger;
import com.technomorph.lck.spi.LedgerAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The meta-test. Does the suite actually catch anything, and does it stay quiet when
 * nothing is wrong?
 *
 * <p>An invariant suite has two failure modes and both are worse than having no suite.
 * A <b>false negative</b> means a client ships with a clean scorecard and loses money.
 * A <b>false positive</b> is worse: it fails a correct ledger, the client's engineers
 * find it, and they are right — which ends the engagement and the practice's credibility
 * in one conversation.
 *
 * <p>Both are asserted on every build.
 */
class MutationTest {

    private static final long SEED = Long.getLong("lck.seed", 42L);

    @Test
    @DisplayName("no false positives: every invariant holds against a known-correct ledger")
    void noFalsePositives() {
        List<Result> rs = Invariants.run(new ReferenceLedger(), SEED);
        List<Result> broke = rs.stream().filter(Result::broke).toList();
        assertTrue(broke.isEmpty(), () ->
                "These invariants failed a ledger believed to be correct. Either the ledger has a "
                + "real defect or — more likely — the invariant is wrong. Fix before shipping:\n"
                + broke.stream().map(r -> "  " + r.id() + " " + r.title() + " — " + r.detail())
                       .reduce((a, b) -> a + "\n" + b).orElse(""));
        assertTrue(rs.stream().filter(r -> r.status() == Status.PASS).count() >= 12,
                "expected at least 12 applicable invariants, saw " + rs.size());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutants")
    @DisplayName("no false negatives: each seeded defect trips its invariant")
    void noFalseNegatives(String label, Mutants.Mutant m) {
        LedgerAdapter mutant = m.factory().get();
        Set<String> broke = new LinkedHashSet<>();
        for (Result r : Invariants.run(mutant, SEED)) if (r.broke()) broke.add(r.id());
        assertTrue(broke.contains(m.expectedInvariant()), () ->
                m.id() + " injects '" + m.defect() + "' but " + m.expectedInvariant()
                + " did not catch it. Broke instead: " + (broke.isEmpty() ? "nothing" : broke)
                + ".\nAn invariant with no mutant that only it catches is not pulling its weight.");
    }

    static Stream<Object[]> mutants() {
        return Mutants.all().stream()
                .map(m -> new Object[]{m.id() + " " + m.expectedInvariant() + " — " + m.defect(), m});
    }
}
