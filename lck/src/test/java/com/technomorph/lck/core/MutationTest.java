package com.technomorph.lck.core;

import com.technomorph.lck.core.Invariants.Result;
import com.technomorph.lck.core.Invariants.Status;
import com.technomorph.lck.examples.ReferenceLedger;
import com.technomorph.lck.spi.LedgerAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        // Not ">= 12": the reference ledger declares every capability, so every invariant in
        // the registry applies and every one must pass. Accepting twelve lets two quietly start
        // skipping — through a
        // capability regression, say — with the build still green. The old message reported
        // rs.size(), which is always 14, and so would have misdirected whoever hit it.
        List<String> notPassing = rs.stream().filter(r -> r.status() != Status.PASS)
                .map(r -> r.id() + " (" + r.status() + ")").toList();
        assertTrue(notPassing.isEmpty(), () ->
                "every invariant must pass the reference ledger; these did not: " + notPassing);
        assertEquals(Invariants.REGISTRY.size(), rs.size());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutants")
    @DisplayName("no false negatives: each seeded defect trips exactly the invariants it should")
    void noFalseNegatives(String label, Mutants.Mutant m) {
        LedgerAdapter mutant = m.factory().get();
        Set<String> broke = new TreeSet<>();
        for (Result r : Invariants.run(mutant, SEED)) if (r.broke()) broke.add(r.id());

        Set<String> missed = new TreeSet<>(m.expected());
        missed.removeAll(broke);
        assertTrue(missed.isEmpty(), () ->
                m.id() + " injects '" + m.defect() + "' but " + missed + " did not catch it. "
                + "Broke instead: " + (broke.isEmpty() ? "nothing" : broke)
                + ".\nAn invariant with no mutant that only it catches is not pulling its weight.");

        // The other half, and the one that was missing: a mutant that reddens half the suite
        // proves nothing about which invariant is load-bearing, and a report where one defect
        // lights up nine rows is not a report anyone can act on.
        Set<String> extra = new TreeSet<>(broke);
        extra.removeAll(m.expected());
        assertTrue(extra.isEmpty(), () ->
                m.id() + " injects one defect but also broke " + extra + ", which it does not "
                + "declare. Either the blast radius is real — in which case name it in the "
                + "mutant's expected set, and say why the coupling exists — or one of those "
                + "invariants is firing on something it should not.");
    }

    static Stream<Object[]> mutants() {
        return Mutants.all().stream()
                .map(m -> new Object[]{m.id() + " " + String.join("+", new TreeSet<>(m.expected()))
                        + " — " + m.defect(), m});
    }

    @Test
    @DisplayName("every invariant has a mutant that demonstrates it catches something")
    void everyInvariantIsJustifiedByAMutant() {
        Set<String> covered = new TreeSet<>();
        Mutants.all().forEach(m -> covered.addAll(m.expected()));
        Set<String> uncovered = new TreeSet<>();
        Invariants.REGISTRY.forEach(i -> { if (!covered.contains(i.id())) uncovered.add(i.id()); });

        assertTrue(uncovered.isEmpty(), () ->
                uncovered + " ship with no mutant, so nothing demonstrates they catch anything. "
                + "Mutants.all() is the evidence for the suite; an invariant absent from it is "
                + "reasoning, not evidence.");
    }
}
