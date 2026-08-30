package com.technomorph.lck.core;

import com.technomorph.lck.core.Invariants.Result;
import com.technomorph.lck.core.Invariants.Severity;
import com.technomorph.lck.core.Invariants.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Ratchet mode decides whether a real team keeps the gate switched on. Test it properly. */
class BaselineTest {

    private static Result r(String id, Status s) {
        return new Result(id, id + " title", Severity.BLOCKER, s, "detail", "symptom", 42, 1);
    }

    @Test
    void regressionsExcludeAcceptedFailures(@TempDir Path dir) throws IOException {
        List<Result> first = List.of(r("INV-04", Status.FAIL), r("INV-06", Status.FAIL));
        Path f = dir.resolve("baseline.txt");
        Baseline.write(f, first);

        Baseline b = Baseline.read(f);
        assertEquals(2, b.size());
        assertTrue(b.regressions(first).isEmpty(), "known failures must not fail the build");

        List<Result> withNew = List.of(r("INV-04", Status.FAIL), r("INV-06", Status.FAIL),
                                       r("INV-09", Status.FAIL));
        assertEquals(List.of("INV-09"), b.regressions(withNew).stream().map(Result::id).toList());
    }

    @Test
    void fixedFailuresAreReportedSoTheFileCanShrink(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("baseline.txt");
        Baseline.write(f, List.of(r("INV-04", Status.FAIL), r("INV-06", Status.FAIL)));
        Baseline b = Baseline.read(f);
        assertEquals(List.of("INV-04"),
                b.fixed(List.of(r("INV-04", Status.PASS), r("INV-06", Status.FAIL))));
    }

    @Test
    void missingBaselineFileIsEmptyNotAnError() throws IOException {
        assertTrue(Baseline.read(Path.of("does-not-exist.txt")).isEmpty());
    }
}
