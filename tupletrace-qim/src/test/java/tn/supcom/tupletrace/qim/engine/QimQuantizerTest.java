package tn.supcom.tupletrace.qim.engine;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static tn.supcom.tupletrace.qim.engine.QimQuantizer.embed;
import static tn.supcom.tupletrace.qim.engine.QimQuantizer.extract;

class QimQuantizerTest {
    private static final long[] STEPS = {1, 2, 3, 5, 7, 10, 25, 100};

    @Test
    void cahierDesChargesExample2137() {
        // temperature = 21,37 ; δ = 0,01 → en unités de 0,01 : u = 2137, δ = 1.
        assertEquals(2137, embed(2137, 1, 1), "bit 1 : 21,37 reste 21,37");
        assertEquals(2138, embed(2137, 1, 0), "bit 0 : 21,37 devient 21,38 (égalité → point supérieur)");
        assertEquals(1, extract(2137, 1));
        assertEquals(0, extract(2138, 1));
    }

    @Test
    void oneHundredThousandValuesIncludingNegativesStayWithinOneStep() {
        var random = new Random(20_261_124);
        int negatives = 0;
        for (int i = 0; i < 100_000; i++) {
            long value = random.nextLong(-1_000_000, 1_000_001);
            long step = STEPS[random.nextInt(STEPS.length)];
            int bit = random.nextInt(2);
            long marked = embed(value, step, bit);
            negatives += value < 0 ? 1 : 0;

            assertTrue(Math.abs(marked - value) <= step, "écart > δ pour u=" + value + " δ=" + step);
            assertEquals(0, Math.floorMod(marked, step), "pas sur la grille");
            assertEquals(bit, Math.floorMod(marked / step, 2), "mauvaise parité");
            assertEquals(bit, extract(marked, step), "bit relu différent");
            // Aucun autre point de la même parité n'est plus proche.
            assertTrue(Math.abs(marked - value) <= Math.abs(marked + 2 * step - value));
            assertTrue(Math.abs(marked - value) <= Math.abs(marked - 2 * step - value));
        }
        assertTrue(negatives > 40_000, "le tirage doit contenir des valeurs négatives");
    }

    @Test
    void negativeValuesUseTheSameParityRule() {
        assertEquals(-2, embed(-3, 2, 1));     // m = -1, impair
        assertEquals(-4, embed(-3, 2, 0));     // m = -2, pair
        assertEquals(1, extract(-2, 2));
        assertEquals(0, extract(-4, 2));
        assertEquals(-2137, embed(-2137, 1, 1));
        assertEquals(-2136, embed(-2137, 1, 0)); // égalité entre -2138 et -2136 → point supérieur
    }

    @Test
    void noiseStrictlyBelowHalfAStepKeepsTheBit() {
        var random = new Random(7);
        for (long step = 1; step <= 12; step++) {
            for (int trial = 0; trial < 2_000; trial++) {
                long value = random.nextLong(-100_000, 100_001);
                int bit = random.nextInt(2);
                long marked = embed(value, step, bit);
                for (long noise = -step; noise <= step; noise++) {
                    if (2 * Math.abs(noise) < step) {
                        assertEquals(bit, extract(marked + noise, step), "δ=" + step + " bruit=" + noise);
                    }
                }
            }
        }
    }

    @Test
    void noiseOfAFullStepDestroysTheBitAsDocumented() {
        long marked = embed(2137, 10, 0);
        assertNotEquals(0, extract(marked + 10, 10));
        assertNotEquals(0, extract(marked - 10, 10));
    }

    @Test
    void boundedEmbeddingPicksTheOtherEquallyClosePointInsideTheRange() {
        assertEquals(100_000, embed(99_999, 1, 0));
        assertEquals(99_998, embed(99_999, 1, 0, -99_999, 99_999));
        assertEquals(-99_998, embed(-99_999, 1, 0, -99_999, 99_999));
        assertThrows(ArithmeticException.class, () -> embed(5, 10, 1, 0, 9));
    }

    @Test
    void extremeLongValuesNeverOverflowSilently() {
        assertEquals(Long.MAX_VALUE - 1, embed(Long.MAX_VALUE, 1, 0));
        assertEquals(Long.MIN_VALUE + 1, embed(Long.MIN_VALUE, 1, 1));
        assertDoesNotThrow(() -> extract(Long.MIN_VALUE, 3));
        assertDoesNotThrow(() -> extract(Long.MAX_VALUE, 3));
    }

    @Test
    void refusesInvalidStepOrBit() {
        assertThrows(IllegalArgumentException.class, () -> embed(1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> embed(1, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> embed(1, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> extract(1, 0));
    }
}
