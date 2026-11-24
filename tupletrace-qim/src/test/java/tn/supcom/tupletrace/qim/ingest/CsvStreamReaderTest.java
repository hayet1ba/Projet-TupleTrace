package tn.supcom.tupletrace.qim.ingest;

import org.junit.jupiter.api.Test;
import tn.supcom.tupletrace.qim.canonical.CanonicalTuple;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.*;
import tn.supcom.tupletrace.qim.ingest.CsvRejectedException.Code;
import tn.supcom.tupletrace.qim.schema.Attribute;
import tn.supcom.tupletrace.qim.schema.AttributeType;
import tn.supcom.tupletrace.qim.schema.DatasetSchema;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class CsvStreamReaderTest {
    private static final UUID DATASET = UUID.fromString("00000000-0000-4000-8000-000000000001");

    /** Jeu synthétique : record_id (CLE), sensor_id, measured_on, temperature (ELIGIBLE), note. */
    private static final DatasetSchema SENSORS = new DatasetSchema(DATASET, 1, List.of(
            Attribute.key(1, "record_id", AttributeType.integer()),
            Attribute.forbidden(2, "sensor_id", AttributeType.category()),
            Attribute.forbidden(3, "measured_on", AttributeType.date()),
            Attribute.eligible(4, "temperature", AttributeType.decimal(5, 2),
                    new BigDecimal("0.05"), new BigDecimal("0.01")),
            Attribute.forbidden(5, "note", AttributeType.text())));
    private static final String HEADER = "record_id,sensor_id,measured_on,temperature,note";

    /** Schéma réduit à une clé et un texte, pour les tests de volume. */
    private static final DatasetSchema KEY_AND_NOTE = new DatasetSchema(DATASET, 1, List.of(
            Attribute.key(1, "id", AttributeType.integer()),
            Attribute.forbidden(2, "note", AttributeType.text())));

    private static String row(long i) {
        return i + ",S" + (i % 20) + ",2026-01-" + String.format("%02d", 1 + i % 28) + ","
                + (i % 400) / 10 + "." + String.format("%02d", i % 100) + ",ok";
    }

    private static String csv(String header, List<String> rows) {
        return header + "\n" + String.join("\n", rows) + "\n";
    }

    private static List<String> rows(int count) {
        return IntStream.range(0, count).mapToObj(CsvStreamReaderTest::row).toList();
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static List<ValidatedBatch> readAll(DatasetSchema schema, CsvFormat format, InputStream in)
            throws IOException {
        var batches = new ArrayList<ValidatedBatch>();
        new CsvStreamReader(schema, format, CsvLimits.DEFAULT).read(in, batches::add);
        return batches;
    }

    private static List<CanonicalTuple> tuples(List<ValidatedBatch> batches) {
        return batches.stream().flatMap(b -> b.tuples().stream()).toList();
    }

    private static CsvRejectedException rejected(DatasetSchema schema, CsvFormat format, InputStream in,
                                                 List<ValidatedBatch> delivered) {
        return assertThrows(CsvRejectedException.class,
                () -> new CsvStreamReader(schema, format, CsvLimits.DEFAULT).read(in, delivered::add));
    }

    private static CsvRejectedException rejected(String text) {
        return rejected(SENSORS, CsvFormat.COMMA_DOT, stream(text), new ArrayList<>());
    }

    @Test
    void deliversBatchesOfFiveHundredTuples() throws IOException {
        var batches = readAll(SENSORS, CsvFormat.COMMA_DOT, stream(csv(HEADER, rows(1001))));
        assertEquals(List.of(500, 500, 1), batches.stream().map(ValidatedBatch::size).toList());
        assertSame(SENSORS, batches.getFirst().schema());

        var first = batches.getFirst().tuples().getFirst();
        assertEquals(new IntegerValue(0), first.value(1).orElseThrow());
        assertEquals(new CategoryValue("S0"), first.value(2).orElseThrow());
        assertEquals(DateValue.of(LocalDate.of(2026, 1, 1)), first.value(3).orElseThrow());
        assertEquals(new DecimalValue(0, 2), first.value(4).orElseThrow());
        assertEquals(new TextValue("ok"), first.value(5).orElseThrow());
    }

    @Test
    void decimalCommaFileGivesTheSameTuplesAsDecimalDotFile() throws IOException {
        var dot = "record_id,sensor_id,measured_on,temperature,note\n1001,S17,2026-01-05,21.37,\"a, b\"\n";
        var comma = "record_id;sensor_id;measured_on;temperature;note\n1001;S17;2026-01-05;21,37;a, b\n";
        var fromDot = tuples(readAll(SENSORS, CsvFormat.COMMA_DOT, stream(dot)));
        var fromComma = tuples(readAll(SENSORS, CsvFormat.SEMICOLON_COMMA, stream(comma)));
        assertEquals(fromDot, fromComma);
        assertEquals(new DecimalValue(2137, 2), fromComma.getFirst().value(4).orElseThrow());
    }

    @Test
    void columnOrderInTheFileDoesNotChangeTheTuples() throws IOException {
        var reference = tuples(readAll(SENSORS, CsvFormat.COMMA_DOT, stream(csv(HEADER, rows(50)))));

        var permutedRows = IntStream.range(0, 50).mapToObj(i -> {
            var f = row(i).split(",");
            return String.join(",", f[3], f[4], f[0], f[2], f[1]);
        }).toList();
        var permuted = tuples(readAll(SENSORS, CsvFormat.COMMA_DOT,
                stream(csv(" TEMPERATURE,note,record_id,measured_on,sensor_id", permutedRows))));
        assertEquals(reference, permuted);
    }

    @Test
    void absentValueIsNullButEmptyQuotedTextIsEmpty() throws IOException {
        var tuple = tuples(readAll(SENSORS, CsvFormat.COMMA_DOT,
                stream(HEADER + "\n1,,2026-01-01,,\"\"\n"))).getFirst();
        assertEquals(CanonicalValue.nullValue(), tuple.value(2).orElseThrow());
        assertEquals(CanonicalValue.nullValue(), tuple.value(4).orElseThrow());
        assertEquals(new TextValue(""), tuple.value(5).orElseThrow());
    }

    @Test
    void duplicateKeySpreadOverTwoBatchesIsRefused() {
        var rows = new ArrayList<>(rows(700));
        rows.set(650, "3,S3,2026-01-04,10.00,ok");     // même record_id que la ligne de données n° 3 (lot 1)
        var delivered = new ArrayList<ValidatedBatch>();
        var refused = rejected(SENSORS, CsvFormat.COMMA_DOT, stream(csv(HEADER, rows)), delivered);

        assertEquals(Code.DUPLICATE_KEY, refused.code());
        assertEquals(652, refused.lineNumber());          // en-tête = ligne 1, donnée n° i = ligne i + 2
        assertEquals(1, delivered.size(), "le premier lot était déjà remis : l'appelant doit l'abandonner");
    }

    @Test
    void duplicateKeyInsideOneBatchIsRefused() {
        assertEquals(Code.DUPLICATE_KEY, rejected(csv(HEADER, List.of(row(1), row(2), row(1)))).code());
    }

    @Test
    void nullKeyIsRefusedAndEmptyQuotedKeyIsNotANumber() {
        assertEquals(Code.NULL_KEY, rejected(HEADER + "\n,S1,2026-01-01,1.00,ok\n").code());
        assertEquals(Code.INVALID_VALUE, rejected(HEADER + "\n\"\",S1,2026-01-01,1.00,ok\n").code());
    }

    @Test
    void refusesBadHeaders() {
        assertEquals(Code.EMPTY_FILE, rejected("").code());
        assertEquals(Code.EMPTY_HEADER, rejected("record_id,,measured_on,temperature,note\n").code());
        assertEquals(Code.EMPTY_HEADER, rejected("record_id,\"  \",measured_on,temperature,note\n").code());
        assertEquals(Code.DUPLICATE_HEADER, rejected(HEADER + ",Temperature\n").code());
        assertEquals(Code.UNKNOWN_COLUMN, rejected(HEADER + ",extra\n").code());
        assertEquals(Code.MISSING_COLUMN, rejected("record_id,sensor_id,measured_on,temperature\n").code());
        assertEquals(1, rejected(HEADER + ",extra\n").lineNumber());
    }

    @Test
    void refusesMalformedRows() {
        var wrongCount = rejected(HEADER + "\n1,S1,2026-01-01,1.00,ok\n2,S1,2026-01-01,1.00\n");
        assertEquals(Code.FIELD_COUNT_MISMATCH, wrongCount.code());
        assertEquals(3, wrongCount.lineNumber());
        assertEquals(Code.BLANK_LINE, rejected(HEADER + "\n1,S1,2026-01-01,1.00,ok\n\n2,S1,2026-01-01,1.00,ok\n").code());
        assertEquals(Code.UNTERMINATED_QUOTE, rejected(HEADER + "\n1,S1,2026-01-01,1.00,\"début\nfin\"\n").code());
        assertEquals(Code.OUT_OF_RANGE, rejected(HEADER + "\n1,S1,2026-01-01,21.375,ok\n").code());
        assertEquals(Code.INVALID_VALUE, rejected(HEADER + "\n1,S1,2026-02-30,21.37,ok\n").code());
    }

    @Test
    void refusesAValueWrittenWithTheUndeclaredDecimalSeparator() {
        var file = "record_id;sensor_id;measured_on;temperature;note\n1001;S17;2026-01-05;21.37;ok\n";
        var refused = rejected(SENSORS, CsvFormat.SEMICOLON_COMMA, stream(file), new ArrayList<>());
        assertEquals(Code.AMBIGUOUS_VALUE, refused.code());
        assertEquals(2, refused.lineNumber());
    }

    @Test
    void refusesTheMillionAndFirstLineWithoutLoadingTheFile() {
        var generated = new GeneratedCsv("id,note", CsvLimits.MAX_LINES + 1, i -> i + ",");
        var delivered = new ArrayList<ValidatedBatch>();
        var refused = rejected(KEY_AND_NOTE, CsvFormat.COMMA_DOT, generated, delivered);

        assertEquals(Code.TOO_MANY_LINES, refused.code());
        assertEquals(CsvLimits.MAX_LINES + 2, refused.lineNumber());
        assertEquals(2_000, delivered.size());
    }

    @Test
    void acceptsExactlyTheLineLimitAndRefusesOneMore() throws IOException {
        var limits = new CsvLimits(CsvLimits.MAX_BYTES, 10, 64, 1024, 4);
        var batches = new ArrayList<ValidatedBatch>();
        assertEquals(10, new CsvStreamReader(KEY_AND_NOTE, CsvFormat.COMMA_DOT, limits)
                .read(new GeneratedCsv("id,note", 10, i -> i + ",x"), batches::add));
        assertEquals(List.of(4, 4, 2), batches.stream().map(ValidatedBatch::size).toList());

        var refused = assertThrows(CsvRejectedException.class, () -> new CsvStreamReader(KEY_AND_NOTE,
                CsvFormat.COMMA_DOT, limits).read(new GeneratedCsv("id,note", 11, i -> i + ",x"), b -> {}));
        assertEquals(Code.TOO_MANY_LINES, refused.code());
    }

    @Test
    void stopsReadingAsSoonAsTheFiftyMegabyteLimitIsExceeded() {
        var padding = "x".repeat(90);
        var generated = new GeneratedCsv("id,note", Long.MAX_VALUE, i -> i + "," + padding);
        var refused = rejected(KEY_AND_NOTE, CsvFormat.COMMA_DOT, generated, new ArrayList<>());

        assertEquals(Code.FILE_TOO_LARGE, refused.code());
        assertTrue(generated.bytesServed() <= CsvLimits.MAX_BYTES + 8192,
                "lu au-delà de la limite : " + generated.bytesServed());
    }

    @Test
    void refusesALineLongerThanSixteenKibibytes() {
        var limitText = "x".repeat(CsvLimits.MAX_LINE_BYTES - "1,".length());
        assertDoesNotThrow(() -> readAll(KEY_AND_NOTE, CsvFormat.COMMA_DOT, stream("id,note\n1," + limitText + "\n")));
        var refused = rejected(KEY_AND_NOTE, CsvFormat.COMMA_DOT,
                stream("id,note\n1,ok\n2," + limitText + "x\n"), new ArrayList<>());
        assertEquals(Code.LINE_TOO_LONG, refused.code());
        assertEquals(3, refused.lineNumber());
    }

    @Test
    void refusesAHeaderWithSixtyFiveColumns() {
        var header = IntStream.rangeClosed(1, 65).mapToObj(i -> "c" + i).collect(Collectors.joining(","));
        assertEquals(Code.TOO_MANY_COLUMNS, rejected(header + "\n").code());
    }

    @Test
    void limitsCanOnlyBeTightenedNeverRaised() {
        assertEquals(new CsvLimits(52_428_800L, 1_000_000L, 64, 16_384, 500), CsvLimits.DEFAULT);
        assertThrows(IllegalArgumentException.class, () -> new CsvLimits(CsvLimits.MAX_BYTES + 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new CsvLimits(1, CsvLimits.MAX_LINES + 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new CsvLimits(1, 1, 65, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new CsvLimits(1, 1, 1, 16_385, 1));
        assertThrows(IllegalArgumentException.class, () -> new CsvLimits(1, 1, 1, 1, 501));
        assertThrows(IllegalArgumentException.class, () -> new CsvLimits(0, 1, 1, 1, 1));
    }

    @Test
    void formatMustDeclareTwoDistinctSeparators() {
        assertThrows(IllegalArgumentException.class, () -> new CsvFormat(',', ','));
        assertThrows(IllegalArgumentException.class, () -> new CsvFormat('"', '.'));
        assertThrows(IllegalArgumentException.class, () -> new CsvFormat(';', ' '));
        assertDoesNotThrow(() -> new CsvFormat('\t', ','));
    }

    @Test
    void validatedBatchCannotBeBuiltOutsideTheIngestPackageNorModified() throws IOException {
        var constructors = ValidatedBatch.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertFalse(Modifier.isPublic(constructors[0].getModifiers()));
        assertFalse(Modifier.isProtected(constructors[0].getModifiers()));

        var batch = readAll(SENSORS, CsvFormat.COMMA_DOT, stream(csv(HEADER, rows(3)))).getFirst();
        assertThrows(UnsupportedOperationException.class, () -> batch.tuples().add(batch.tuples().getFirst()));
    }
}
