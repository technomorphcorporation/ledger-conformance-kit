package com.technomorph.lck.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every finding the kit produces is formatted through {@link Invariants#money}, so an error
 * here is an error in the product's entire output.
 */
class MoneyTest {

    @ParameterizedTest(name = "{0} subunits of {1} reads as {2}")
    @CsvSource({
            "50000,   USD, 500.00",
            "498,     USD, 4.98",
            "0,       USD, 0.00",
            "5,       USD, 0.05",
            "-2000,   USD, -20.00",
            "-1,      USD, -0.01",
            // JPY has no minor unit: 498 yen is 498, not 4.98.
            "498,     JPY, 498",
            "50000,   JPY, 50000",
            // KWD has three.
            "498,     KWD, 0.498",
            "1234567, KWD, 1234.567",
            // An unknown code must not throw in the middle of reporting a finding.
            "498,     ZZZ, 4.98",
    })
    void formatsByCurrencyExponent(long subunits, String currency, String expected) {
        assertEquals(expected, Invariants.money(subunits, currency));
    }

    @Test
    @DisplayName("no double: exact above the 53-bit mantissa where floating point starts lying")
    void exactBeyondDoublePrecision() {
        // 2^53 + 1 subunits. Via `subunits / 100.0` this rounds and the last digits are wrong.
        assertEquals("90071992547409.93", Invariants.money(9_007_199_254_740_993L));
        assertEquals("92233720368547758.07", Invariants.money(Long.MAX_VALUE));
        assertEquals("-92233720368547758.08", Invariants.money(Long.MIN_VALUE),
                "Long.MIN_VALUE has no positive counterpart; negating it silently overflows");
    }

    @Test
    @DisplayName("the formatter itself does not disagree with integer arithmetic")
    void agreesWithIntegerArithmeticAcrossTheRange() {
        for (long i = -1_000_000; i <= 1_000_000; i += 7_919) {   // prime step, no lucky alignment
            final long v = i;
            String s = Invariants.money(v);
            long back = Long.parseLong(s.replace(".", "").replace("-", ""));
            assertEquals(Math.abs(v), back, () -> "round-trip lost value at " + v);
            assertEquals(v < 0, s.startsWith("-"), () -> "sign lost at " + v);
        }
    }

    @Test
    @DisplayName("defaults to USD, because every leg the suite posts is USD")
    void defaultsToUsd() {
        assertEquals(Invariants.money(1234, "USD"), Invariants.money(1234));
    }
}
