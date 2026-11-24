package tn.supcom.tupletrace.qim.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tn.supcom.tupletrace.qim.canonical.CanonicalTuple;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue;
import tn.supcom.tupletrace.qim.engine.MarkingRefusedException.Code;
import tn.supcom.tupletrace.qim.export.CsvWriter;
import tn.supcom.tupletrace.qim.ingest.CsvFormat;
import tn.supcom.tupletrace.qim.ingest.CsvLimits;
import tn.supcom.tupletrace.qim.ingest.CsvStreamReader;
import tn.supcom.tupletrace.qim.ingest.ValidatedBatch;
import tn.supcom.tupletrace.qim.schema.Attribute;
import tn.supcom.tupletrace.qim.schema.AttributeType;
import tn.supcom.tupletrace.qim.schema.DatasetSchema;
import tn.supcom.tupletrace.qim.schema.Role;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Insertion du filigrane sur un jeu synthétique (aucune donnée réelle, graine fixe). */
class MarkingEngineTest {
    private static final UUID DATASET = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final int ROWS = 3_000;

    private static final DatasetSchema SENSORS = new DatasetSchema(DATASET, 1, List.of(
            Attribute.key(1, "record_id", AttributeType.integer()),
            Attribute.forbidden(2, "sensor_id", AttributeType.category()),
            Attribute.forbidden(3, "measured_on", AttributeType.date()),
            Attribute.eligible(4, "temperature", AttributeType.decimal(5, 2), new BigDecimal("0.05"), new BigDecimal("0.01")),
            Attribute.eligible(5, "humidity", AttributeType.decimal(5, 2), new BigDecimal("0.10"), new BigDecimal("0.02")),
            Attribute.eligible(6, "count", AttributeType.integer(), new BigDecimal("2"), new BigDecimal("1")),
            Attribute.forbidden(7, "note", AttributeType.text())));

    private MarkingKey key;

    @BeforeEach
    void generateKey() {
        key = MarkingKey.generate();
    }

    @AfterEach
    void eraseKey() {
        key.close();
    }

    /** CSV synthétique déjà en forme canonique : relu puis réécrit, il redonne les mêmes octets. */
    static String syntheticCsv(int rows, long seed) {
        var random = new Random(seed);
        var csv = new StringBuilder("record_id,sensor_id,measured_on,temperature,humidity,count,note\n");
        for (int i = 0; i < rows; i++) {
            var temperature = i % 97 == 0 ? "" : BigDecimal.valueOf(random.nextInt(-4_000, 6_001), 2).toPlainString();
            csv.append(i).append(",S").append(random.nextInt(20))
                    .append(",2026-").append(String.format("%02d-%02d", 1 + random.nextInt(12), 1 + random.nextInt(28)))
                    .append(',').append(temperature)
                    .append(',').append(BigDecimal.valueOf(random.nextInt(0, 10_001), 2).toPlainString())
                    .append(',').append(random.nextInt(-50, 501))
                    .append(",note-").append(i).append('\n');
        }
        return csv.toString();
    }

    private record Protection(List<CanonicalTuple> original, List<CanonicalTuple> marked, QualityReport report) {}

    private static Protection protect(MarkingKey with, DatasetSchema schema, String csv) throws IOException {
        var engine = new MarkingEngine(with, schema, MarkingParameters.DEFAULT);
        var original = new ArrayList<CanonicalTuple>();
        var marked = new ArrayList<CanonicalTuple>();
        new CsvStreamReader(schema, CsvFormat.COMMA_DOT, CsvLimits.DEFAULT).read(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), batch -> {
                    original.addAll(batch.tuples());
                    marked.addAll(engine.protect(batch).tuples());
                });
        return new Protection(original, marked, engine.report());
    }

    static String write(DatasetSchema schema, List<CanonicalTuple> tuples) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var writer = new CsvWriter(bytes, schema, CsvFormat.COMMA_DOT);
        writer.writeAll(tuples);
        writer.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static long units(CanonicalValue value) {
        return switch (value) {
            case CanonicalValue.IntegerValue integer -> integer.value();
            case CanonicalValue.DecimalValue decimal -> decimal.unscaled();
            default -> throw new AssertionError("valeur non numérique");
        };
    }

    @Test
    void keyAndForbiddenColumnsAreUnchangedByteForByte() throws IOException {
        var csv = syntheticCsv(ROWS, 1);
        var result = protect(key, SENSORS, csv);
        assertEquals(csv, write(SENSORS, result.original()), "le jeu synthétique est déjà canonique");

        var inputLines = csv.split("\n");
        var copyLines = write(SENSORS, result.marked()).split("\n");
        assertEquals(inputLines.length, copyLines.length);
        assertEquals(inputLines[0], copyLines[0], "en-tête identique");

        var attributes = SENSORS.attributes();
        int changedLines = 0;
        for (int line = 1; line < inputLines.length; line++) {
            var before = inputLines[line].split(",", -1);
            var after = copyLines[line].split(",", -1);
            for (int column = 0; column < attributes.size(); column++) {
                if (attributes.get(column).role() != Role.ELIGIBLE) {
                    assertEquals(before[column], after[column], "ligne " + line + ", " + attributes.get(column).name());
                }
            }
            changedLines += inputLines[line].equals(copyLines[line]) ? 0 : 1;
        }
        assertTrue(changedLines > 50, "lignes modifiées : " + changedLines);
    }

    @Test
    void everyCellStaysWithinStepAndToleranceAndKeepsItsType() throws IOException {
        var result = protect(key, SENSORS, syntheticCsv(ROWS, 2));
        for (int i = 0; i < ROWS; i++) {
            var before = result.original().get(i);
            var after = result.marked().get(i);
            for (var attribute : SENSORS.attributes()) {
                var oldValue = before.value(attribute.attributeId()).orElseThrow();
                var newValue = after.value(attribute.attributeId()).orElseThrow();
                if (attribute.role() != Role.ELIGIBLE || oldValue instanceof CanonicalValue.NullValue) {
                    assertEquals(oldValue, newValue);
                    continue;
                }
                assertEquals(oldValue.getClass(), newValue.getClass(), "le type ne change pas");
                var column = new QimColumn(attribute);
                var deviation = column.toColumnUnit(Math.abs(units(newValue) - units(oldValue)));
                assertTrue(deviation.compareTo(attribute.step()) <= 0, "écart > δ");
                assertTrue(deviation.compareTo(attribute.tolerance()) <= 0, "écart > T");
            }
        }
    }

    @Test
    void onlyWatermarkRowsChangeAndTheyCarryTheExpectedBit() throws IOException {
        var result = protect(key, SENSORS, syntheticCsv(ROWS, 3));
        var planner = new MarkingPlanner(key, SENSORS, MarkingParameters.DEFAULT);
        int watermarkCells = 0;
        for (int i = 0; i < ROWS; i++) {
            var before = result.original().get(i);
            var after = result.marked().get(i);
            var placement = planner.placementOf(before);
            if (placement.isEmpty() || placement.get().layer() != Layer.WATERMARK) {
                assertEquals(before, after, "une ligne hors filigrane ne change pas");
                continue;
            }
            var column = new QimColumn(placement.get().column());
            var value = after.value(column.attribute().attributeId()).orElseThrow();
            if (value instanceof CanonicalValue.NullValue) {
                continue;
            }
            watermarkCells++;
            assertEquals(placement.get().watermarkBit(), column.extract(units(value)));
        }
        // ≈ 3 000 × 1/5 × 1/3 = 200 cellules.
        assertTrue(watermarkCells > 120 && watermarkCells < 300, "cellules du filigrane : " + watermarkCells);
        assertEquals(watermarkCells, result.report().markedCells());
    }

    @Test
    void qualityReportDescribesTheDistortion() throws IOException {
        var report = protect(key, SENSORS, syntheticCsv(ROWS, 4)).report();
        assertEquals(ROWS, report.tuples());
        assertEquals(report.watermarkRows(), report.markedCells() + report.skippedNullCells());
        assertTrue(report.selectedRows() > report.watermarkRows());
        assertEquals(3, report.columns().size());

        for (var quality : report.columns()) {
            var attribute = SENSORS.attribute(quality.attributeId()).orElseThrow();
            assertTrue(quality.maxAbsoluteError().compareTo(attribute.step()) <= 0, quality.name());
            assertTrue(quality.meanAbsoluteError().compareTo(attribute.step()) <= 0, quality.name());
            assertTrue(quality.changedCells() <= quality.markedCells());
            assertTrue(Math.abs(quality.meanAfter() - quality.meanBefore()) <= attribute.step().doubleValue());
            assertTrue(quality.minAfter().subtract(quality.minBefore()).abs().compareTo(attribute.step()) <= 0);
            assertTrue(quality.maxAfter().subtract(quality.maxBefore()).abs().compareTo(attribute.step()) <= 0);
            assertTrue(quality.varianceBefore() > 0);
        }
        var temperature = report.columns().getFirst();
        assertEquals("temperature", temperature.name());
        assertEquals(ROWS - (ROWS + 96) / 97, temperature.values(), "les cellules vides ne sont pas comptées");
    }

    @Test
    void sameKeyGivesTheSameCopyAndAnotherKeyAnotherCopy() throws IOException {
        var csv = syntheticCsv(ROWS, 5);
        var first = write(SENSORS, protect(key, SENSORS, csv).marked());
        assertEquals(first, write(SENSORS, protect(key, SENSORS, csv).marked()));
        try (var other = MarkingKey.generate()) {
            assertNotEquals(first, write(SENSORS, protect(other, SENSORS, csv).marked()));
        }
    }

    @Test
    void refusesTheWholeJobWhenThePrecisionWouldBeExceeded() {
        var tight = new DatasetSchema(DATASET, 1, List.of(
                Attribute.key(1, "record_id", AttributeType.integer()),
                Attribute.eligible(4, "temperature", AttributeType.decimal(5, 2),
                        new BigDecimal("0.05"), new BigDecimal("0.02"))));
        for (var extreme : List.of("999.99", "-999.99")) {
            var csv = new StringBuilder("record_id,temperature\n");
            for (int i = 0; i < ROWS; i++) {
                csv.append(i).append(',').append(extreme).append('\n');
            }
            var refused = assertThrows(MarkingRefusedException.class, () -> protect(key, tight, csv.toString()));
            assertEquals(Code.PRECISION_EXCEEDED, refused.code());
            assertEquals(4, refused.attributeId());
            assertFalse(refused.getMessage().contains("999"), "le message ne contient pas la valeur");
        }
    }

    @Test
    void extremeValuesAreAcceptedWhenAnEquallyClosePointFitsTheType() throws IOException {
        var csv = new StringBuilder("record_id,temperature\n");
        for (int i = 0; i < ROWS; i++) {
            csv.append(i).append(',').append(i % 2 == 0 ? "999.99" : "-999.99").append('\n');
        }
        var schema = new DatasetSchema(DATASET, 1, List.of(
                Attribute.key(1, "record_id", AttributeType.integer()),
                Attribute.eligible(4, "temperature", AttributeType.decimal(5, 2),
                        new BigDecimal("0.05"), new BigDecimal("0.01"))));
        var result = protect(key, schema, csv.toString());
        assertTrue(result.report().columns().getFirst().changedCells() > 0);
        assertEquals(new BigDecimal("999.99"), result.report().columns().getFirst().maxAfter());
        assertEquals(new BigDecimal("-999.99"), result.report().columns().getFirst().minAfter());
    }

    @Test
    void refusesBatchesOfAnotherSchemaVersion() throws IOException {
        var version2 = new DatasetSchema(DATASET, 2, SENSORS.attributes());
        var engine = new MarkingEngine(key, SENSORS, MarkingParameters.DEFAULT);
        var batches = new ArrayList<ValidatedBatch>();
        new CsvStreamReader(version2, CsvFormat.COMMA_DOT, CsvLimits.DEFAULT).read(
                new ByteArrayInputStream(syntheticCsv(10, 6).getBytes(StandardCharsets.UTF_8)), batches::add);
        var refused = assertThrows(MarkingRefusedException.class, () -> engine.protect(batches.getFirst()));
        assertEquals(Code.SCHEMA_MISMATCH, refused.code());
    }

    @Test
    void refusesASchemaWithoutEligibleColumn() {
        var nothingToMark = new DatasetSchema(DATASET, 1, List.of(
                Attribute.key(1, "record_id", AttributeType.integer()),
                Attribute.forbidden(2, "note", AttributeType.text())));
        var refused = assertThrows(MarkingRefusedException.class,
                () -> new MarkingEngine(key, nothingToMark, MarkingParameters.DEFAULT));
        assertEquals(Code.NO_ELIGIBLE_COLUMN, refused.code());
    }

    @Test
    void markedBatchCannotBeBuiltOutsideTheEngine() {
        for (var constructor : MarkedBatch.class.getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(constructor.getModifiers()));
            assertFalse(Modifier.isProtected(constructor.getModifiers()));
        }
    }
}
