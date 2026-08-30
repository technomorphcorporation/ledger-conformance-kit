package com.technomorph.lck.junit5;

import org.junit.jupiter.api.TestTemplate;

/**
 * Extend this (or copy the single method) so JUnit has a template to expand into one
 * test per invariant.
 *
 * <pre>{@code
 * @LedgerConformance(adapter = AcmeLedgerAdapter.class)
 * class LedgerConformanceTest extends ConformanceTests { }
 * }</pre>
 */
public abstract class ConformanceTests {
    @TestTemplate
    void invariant() {
        // Body is intentionally empty: the extension supplies one invocation per
        // invariant and asserts inside a BeforeTestExecutionCallback.
    }
}
