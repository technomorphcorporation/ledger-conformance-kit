package com.technomorph.lck.junit5;

import com.technomorph.lck.spi.LedgerAdapter;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.*;

/**
 * Drop this on a test class and the whole suite becomes ordinary JUnit tests — one per
 * invariant, named by ref. Existing CI reporting, flaky-test history and IDE integration
 * then work with no further wiring, which is the entire point.
 *
 * <pre>{@code
 * @LedgerConformance(adapter = AcmeLedgerAdapter.class, seed = 42)
 * class LedgerConformanceTest { }
 * }</pre>
 *
 * The extension runs the adapter TCK first. If the adapter itself is wrong, every
 * conformance test is skipped with the TCK failure as the reason — reporting a red
 * scorecard produced by a broken adapter is worse than reporting nothing.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(LedgerConformanceExtension.class)
public @interface LedgerConformance {

    Class<? extends LedgerAdapter> adapter();

    /** Fixed by default so CI is reproducible. Override in the nightly soak. */
    long seed() default 42L;

    /** Fail only on failures absent from this baseline file. See --baseline in the CLI. */
    String baseline() default "";

    /** Skip invariants above this severity budget, for teams adopting incrementally. */
    Severity failOn() default Severity.BLOCKER;

    enum Severity { BLOCKER, MAJOR, MINOR }
}
