package tn.supcom.tupletrace.qim.detect;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tn.supcom.tupletrace.qim.canonical.CanonicalTuple;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.IntegerValue;
import tn.supcom.tupletrace.qim.detect.DetectionResult.Reason;
import tn.supcom.tupletrace.qim.engine.Layer;
import tn.supcom.tupletrace.qim.engine.MarkingKey;
import tn.supcom.tupletrace.qim.engine.MarkingParameters;
import tn.supcom.tupletrace.qim.engine.MarkingPlanner;
import tn.supcom.tupletrace.qim.ingest.CsvFormat;
import tn.supcom.tupletrace.qim.ingest.CsvLimits;
import tn.supcom.tupletrace.qim.ingest.SuspectSummary;
import tn.supcom.tupletrace.qim.testing.SyntheticSensors;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class WatermarkDetectorTest {
    private static final int ROWS = 3_000;
    private static String original;

    private MarkingKey key;
    private String copy;

    @BeforeAll
    static void generateDataset() {
        original = SyntheticSensors.csv(ROWS, 11);
    }

    @BeforeEach
    void markACopy() throws IOException {
        key = MarkingKey.generate();
        copy = SyntheticSensors.markedCopy(key, original);
    }

    @AfterEach
    void eraseKey() {
        key.close();
    }

    private static DetectionResult detect(MarkingKey with, String csv) throws IOException {
        return new WatermarkDetector(with, SyntheticSensors.SCHEMA, MarkingParameters.DEFAULT).detect(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), CsvFormat.COMMA_DOT, CsvLimits.DEFAULT);
    }

    private static List<String> dataLines(String csv) {
        var lines = csv.split("\n");
        return new ArrayList<>(Arrays.asList(lines).subList(1, lines.length));
    }

    private static String join(String header, List<String> lines) {
        return header + "\n" + String.join("\n", lines) + "\n";
    }

    /** Réordonne les colonnes : la colonne {@code order[j]} de l'original devient la j-ième. */
    private static String permuteColumns(String csv, int... order) {
        return Arrays.stream(csv.split("\n")).map(line -> {
            var fields = line.split(",", -1);
            return Arrays.stream(order).mapToObj(i -> fields[i]).collect(Collectors.joining(","));
        }).collect(Collectors.joining("\n", "", "\n"));
    }

    @Test
    void markedCopyIsPositiveWithEveryBitCorrect() throws IOException {
        var result = detect(key, copy);
        assertEquals(Verdict.POSITIF, result.verdict());
        assertEquals("Marque reconnue", result.verdict().label());
        assertEquals(Reason.NONE, result.reason());
        assertEquals(result.bitsRead(), result.bitsMatching(), "100 % de bits corrects");
        assertTrue(result.bitsRead() > 120 && result.bitsRead() < 300, "n = " + result.bitsRead());
        assertTrue(result.pValue() < DetectionResult.THRESHOLD);
        assertEquals(-result.bitsRead() * Math.log10(2), result.log10PValue(), 1e-6);
        assertEquals(ROWS, result.extract().rowsRead());
    }

    @Test
    void unmarkedOriginalGivesRandomBitsAndIsNegative() throws IOException {
        var result = detect(key, original);
        assertEquals(Verdict.NEGATIF, result.verdict());
        assertEquals("Aucune marque détectée", result.verdict().label());
        assertTrue(result.bitsRead() >= DetectionResult.MINIMUM_BITS);
        double share = result.bitsMatching() / (double) result.bitsRead();
        assertTrue(share > 0.3 && share < 0.7, "concordance proche du hasard : " + share);
        assertTrue(result.pValue() >= DetectionResult.THRESHOLD);
    }

    @Test
    void wrongKeyIsNegative() throws IOException {
        try (var other = MarkingKey.generate()) {
            var result = detect(other, copy);
            assertEquals(Verdict.NEGATIF, result.verdict());
            assertTrue(result.bitsRead() >= DetectionResult.MINIMUM_BITS);
        }
    }

    @Test
    void tooFewBitsIsIndeterminateEvenWhenAllOfThemMatch() throws IOException {
        var result = detect(key, join(SyntheticSensors.HEADER, dataLines(copy).subList(0, 40)));
        assertEquals(Verdict.INDETERMINE, result.verdict());
        assertEquals(Reason.INSUFFICIENT_BITS, result.reason());
        assertTrue(result.bitsRead() < DetectionResult.MINIMUM_BITS);
        assertEquals(result.bitsRead(), result.bitsMatching());
    }

    @Test
    void decisionRuleAroundTwentyBits() {
        var readable = new SuspectSummary(100, 0, 0, 0, true, false);
        assertEquals(Verdict.INDETERMINE, DetectionResult.decide(19, 19, readable).verdict());
        assertEquals(Verdict.POSITIF, DetectionResult.decide(20, 20, readable).verdict());
        assertEquals(Verdict.NEGATIF, DetectionResult.decide(20, 19, readable).verdict());
        assertEquals(Verdict.NEGATIF, DetectionResult.decide(200, 100, readable).verdict());
    }

    @Test
    void missingKeyColumnIsIndeterminate() throws IOException {
        var withoutKey = permuteColumns(copy, 1, 2, 3, 4, 5, 6);
        var result = detect(key, withoutKey);
        assertEquals(Verdict.INDETERMINE, result.verdict());
        assertEquals(Reason.MISSING_KEY_COLUMN, result.reason());
        assertEquals(0, result.bitsRead());
    }

    /** Indice d'une ligne qui porte un bit du filigrane lisible (pour rendre les tests précis). */
    private int watermarkLine(List<String> lines) {
        var planner = new MarkingPlanner(key, SyntheticSensors.SCHEMA, MarkingParameters.DEFAULT);
        for (int i = 0; i < lines.size(); i++) {
            var fields = lines.get(i).split(",", -1);
            var placement = planner.placementOf(CanonicalTuple.of(Map.of(1, new IntegerValue(Long.parseLong(fields[0])))));
            if (placement.isPresent() && placement.get().layer() == Layer.WATERMARK
                    && !fields[placement.get().column().attributeId() - 1].isEmpty()) {
                return i;
            }
        }
        throw new AssertionError("aucune ligne du filigrane");
    }

    @Test
    void duplicatedLineInAMarkedExtractIsExcludedAndTheVerdictStaysPositive() throws IOException {
        var reference = detect(key, copy);
        var lines = dataLines(copy);
        lines.add(lines.get(watermarkLine(lines)));

        var result = detect(key, join(SyntheticSensors.HEADER, lines));
        assertEquals(Verdict.POSITIF, result.verdict());
        assertEquals(Reason.NONE, result.reason());
        assertEquals(2, result.excludedDuplicateRows(), "les deux occurrences sont écartées");
        assertEquals(1, result.extract().repeatedKeys());
        assertEquals(reference.bitsRead() - 1, result.bitsRead(), "la ligne ambiguë ne compte plus");
        assertEquals(result.bitsRead(), result.bitsMatching());
    }

    @Test
    void aTamperedDuplicateCannotInjectAWrongBit() throws IOException {
        var lines = dataLines(copy);
        int index = watermarkLine(lines);
        var fields = lines.get(index).split(",", -1);
        var planner = new MarkingPlanner(key, SyntheticSensors.SCHEMA, MarkingParameters.DEFAULT);
        var column = planner.placementOf(CanonicalTuple.of(Map.of(1, new IntegerValue(Long.parseLong(fields[0])))))
                .orElseThrow().column();
        int position = column.attributeId() - 1;
        fields[position] = new BigDecimal(fields[position]).add(column.step()).toPlainString(); // un pas δ : bit inversé
        lines.add(0, String.join(",", fields));     // la version retouchée arrive même en premier

        var result = detect(key, join(SyntheticSensors.HEADER, lines));
        assertEquals(Verdict.POSITIF, result.verdict());
        assertEquals(result.bitsRead(), result.bitsMatching(), "aucun faux bit n'a été compté");
        assertEquals(2, result.excludedDuplicateRows());
    }

    @Test
    void anExtractMadeOnlyOfDuplicatesIsIndeterminate() throws IOException {
        var lines = dataLines(copy);
        lines.addAll(dataLines(copy));
        var result = detect(key, join(SyntheticSensors.HEADER, lines));
        assertEquals(Verdict.INDETERMINE, result.verdict());
        assertEquals(Reason.INSUFFICIENT_BITS, result.reason());
        assertEquals(0, result.bitsRead());
        assertEquals(2L * ROWS, result.excludedDuplicateRows());
    }

    @Test
    void repeatedColumnNameIsIndeterminate() throws IOException {
        var result = detect(key, permuteColumns(copy, 0, 1, 2, 3, 4, 5, 6, 3));
        assertEquals(Verdict.INDETERMINE, result.verdict());
        assertEquals(Reason.AMBIGUOUS_HEADER, result.reason());
    }

    @Test
    void permutingRowsAndColumnsStillGivesPositiveWithTheSameCounts() throws IOException {
        var reference = detect(key, copy);

        var lines = dataLines(copy);
        Collections.shuffle(lines, new Random(2026));
        var permuted = permuteColumns(join(SyntheticSensors.HEADER, lines), 5, 3, 6, 0, 2, 4, 1);
        assertNotEquals(copy, permuted);

        var result = detect(key, permuted);
        assertEquals(Verdict.POSITIF, result.verdict());
        assertEquals(reference.bitsRead(), result.bitsRead());
        assertEquals(reference.bitsMatching(), result.bitsMatching());
    }

    @Test
    void halfOfTheRowsAreStillEnough() throws IOException {
        var lines = dataLines(copy);
        Collections.shuffle(lines, new Random(5));
        var result = detect(key, join(SyntheticSensors.HEADER, lines.subList(0, ROWS / 2)));
        assertEquals(Verdict.POSITIF, result.verdict());
        assertEquals(result.bitsRead(), result.bitsMatching());
    }

    @Test
    void noiseBelowHalfAStepDoesNotEraseTheMark() throws IOException {
        var random = new Random(99);
        var noisy = new ArrayList<String>();
        for (var line : dataLines(copy)) {
            var f = line.split(",", -1);
            if (!f[3].isEmpty()) {
                f[3] = new BigDecimal(f[3]).add(BigDecimal.valueOf(random.nextInt(-4, 5), 3)).toPlainString();
            }
            f[4] = new BigDecimal(f[4]).add(BigDecimal.valueOf(random.nextInt(-9, 10), 3)).toPlainString();
            f[5] = new BigDecimal(f[5]).add(BigDecimal.valueOf(random.nextInt(-4, 5), 1)).toPlainString();
            noisy.add(String.join(",", f));
        }
        var result = detect(key, join(SyntheticSensors.HEADER, noisy));
        assertEquals(Verdict.POSITIF, result.verdict());
        assertEquals(result.bitsRead(), result.bitsMatching(), "un bruit < δ/2 ne change aucun bit");
    }

    @Test
    void ignoresUnknownColumnsAndCountsUnusableRows() throws IOException {
        var lines = dataLines(copy);
        lines.add(17, "pas,une,ligne");
        lines.add(42, ",S1,2026-01-01,1.00,1.00,1,clé vide");
        lines.add(99, "x\"y,S1,2026-01-01,1.00,1.00,1,guillemet");
        var extended = join(SyntheticSensors.HEADER, lines).replace("\n", ",extra\n").replaceFirst(",extra\n", ",commentaire\n");
        var result = detect(key, extended.replace("record_id", " RECORD_ID"));
        assertEquals(Verdict.POSITIF, result.verdict());
        assertEquals(3, result.extract().rowsSkipped());
        assertEquals(ROWS + 3, result.extract().rowsRead());
    }

    @Test
    void emptyExtractIsIndeterminate() throws IOException {
        var result = detect(key, "");
        assertEquals(Verdict.INDETERMINE, result.verdict());
        assertEquals(Reason.MISSING_KEY_COLUMN, result.reason());
        assertEquals(Verdict.INDETERMINE, detect(key, SyntheticSensors.HEADER + "\n").verdict());
    }
}
