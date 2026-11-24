package tn.supcom.tupletrace.qim.detect;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

import static org.junit.jupiter.api.Assertions.*;

class BinomialTestTest {
    private static final MathContext PRECISION = new MathContext(50);

    /** Valeur exacte : somme des C(n, i) pour i ≥ k, divisée par 2^n, en BigInteger. */
    private static BigDecimal exactUpperTail(int n, int k) {
        var sum = BigInteger.ZERO;
        var binomial = BigInteger.ONE;                       // C(n, 0)
        for (int i = 0; i <= n; i++) {
            if (i >= k) {
                sum = sum.add(binomial);
            }
            binomial = binomial.multiply(BigInteger.valueOf(n - i)).divide(BigInteger.valueOf(i + 1));
        }
        return new BigDecimal(sum).divide(new BigDecimal(BigInteger.TWO.pow(n)), PRECISION);
    }

    private static void assertCloseToExact(int n, int k) {
        var exact = exactUpperTail(n, k);
        double computed = BinomialTest.upperTail(n, k);
        double relativeError = Math.abs(computed - exact.doubleValue()) / exact.doubleValue();
        assertTrue(relativeError < 1e-9, "n=" + n + " k=" + k + " exact=" + exact + " calculé=" + computed);

        double exactLog10 = Math.log10(exact.doubleValue());
        assertEquals(exactLog10, BinomialTest.log10UpperTail(n, k), 1e-9 * Math.max(1, Math.abs(exactLog10)));
    }

    @Test
    void matchesExactBigIntegerValuesForEveryKUpToSixty() {
        for (int n = 0; n <= 60; n++) {
            for (int k = 0; k <= n; k++) {
                assertCloseToExact(n, k);
            }
        }
    }

    @Test
    void matchesExactValuesForLargerSamples() {
        for (int k : new int[] {0, 1, 100, 110, 130, 150, 199, 200}) {
            assertCloseToExact(200, k);
        }
        for (int k : new int[] {500, 550, 600, 700, 800}) {
            assertCloseToExact(1_000, k);
        }
    }

    @Test
    void twentyBitsIsTheSmallestSampleThatCanBeatTheThreshold() {
        assertEquals(Math.pow(2, -20), BinomialTest.upperTail(20, 20), 1e-21);
        assertTrue(BinomialTest.upperTail(20, 20) < DetectionResult.THRESHOLD);
        assertTrue(BinomialTest.upperTail(19, 19) > DetectionResult.THRESHOLD);
    }

    @Test
    void edgeCases() {
        assertEquals(1.0, BinomialTest.upperTail(0, 0));
        assertEquals(1.0, BinomialTest.upperTail(500, 0));
        assertEquals(0.5, BinomialTest.upperTail(1, 1), 1e-15);
        assertEquals(0.75, BinomialTest.upperTail(2, 1), 1e-15);
        assertThrows(IllegalArgumentException.class, () -> BinomialTest.upperTail(5, 6));
        assertThrows(IllegalArgumentException.class, () -> BinomialTest.upperTail(5, -1));
        assertThrows(IllegalArgumentException.class, () -> BinomialTest.upperTail(-1, 0));
    }

    @Test
    void keepsAnExactLogarithmWhenThePValueUnderflows() {
        // P(X ≥ 2000 | n = 2000) = 2^-2000 : trop petit pour un double, mais son log10 reste exact.
        assertEquals(0.0, BinomialTest.upperTail(2_000, 2_000));
        assertEquals(-2_000 * Math.log10(2), BinomialTest.log10UpperTail(2_000, 2_000), 1e-6);
        // Grand échantillon équilibré : la p-valeur reste proche de 1/2.
        assertEquals(0.5, BinomialTest.upperTail(70_001, 35_001), 1e-2);
    }
}
