package tn.supcom.tupletrace.qim.ingest;

import org.junit.jupiter.api.Test;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.IntegerValue;
import tn.supcom.tupletrace.qim.ingest.CsvRejectedException.Code;
import tn.supcom.tupletrace.qim.schema.Attribute;
import tn.supcom.tupletrace.qim.schema.AttributeType;
import tn.supcom.tupletrace.qim.schema.DatasetSchema;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SuspectCsvReaderTest {
    private static final DatasetSchema SCHEMA = new DatasetSchema(
            UUID.fromString("00000000-0000-4000-8000-000000000001"), 1, List.of(
            Attribute.key(1, "record_id", AttributeType.integer()),
            Attribute.forbidden(2, "sensor_id", AttributeType.category()),
            Attribute.eligible(4, "temperature", AttributeType.decimal(5, 2), new BigDecimal("0.05"), new BigDecimal("0.01"))));

    private record Reading(SuspectSummary summary, List<SuspectRow> rows) {}

    private static Reading read(CsvFormat format, String csv) throws IOException {
        var rows = new ArrayList<SuspectRow>();
        var summary = new SuspectCsvReader(SCHEMA, format, CsvLimits.DEFAULT)
                .read(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), rows::add);
        return new Reading(summary, rows);
    }

    @Test
    void toleratesMissingNonKeyColumnsExtraColumnsAndAnyOrder() throws IOException {
        var reading = read(CsvFormat.COMMA_DOT, "extra,temperature,record_id\nx,21.37,1\ny,,2\n");
        assertEquals(new SuspectSummary(2, 0, 0, true, false), reading.summary());
        assertEquals(new IntegerValue(1), reading.rows().getFirst().key().value(1).orElseThrow());
        assertEquals(new BigDecimal("21.37"), reading.rows().getFirst().number(4).orElseThrow());
        assertTrue(reading.rows().get(1).number(4).isEmpty());
        assertTrue(reading.rows().getFirst().key().value(2).isEmpty(), "la clé ne contient que les colonnes CLE");
    }

    @Test
    void acceptsNoisyNumbersButDropsAmbiguousOnes() throws IOException {
        var reading = read(CsvFormat.SEMICOLON_COMMA, "record_id;temperature\n1;21,3749\n2;21.37\n3;abc\n");
        assertEquals(new BigDecimal("21.3749"), reading.rows().getFirst().number(4).orElseThrow());
        assertTrue(reading.rows().get(1).number(4).isEmpty(), "« 21.37 » avec la virgule déclarée est ambigu");
        assertTrue(reading.rows().get(2).number(4).isEmpty());
        assertEquals(0, reading.summary().rowsSkipped());
    }

    @Test
    void reportsMissingKeyColumnAndRepeatedHeaderWithoutDeliveringRows() throws IOException {
        var noKey = read(CsvFormat.COMMA_DOT, "sensor_id,temperature\nS1,21.37\n");
        assertFalse(noKey.summary().keyColumnsPresent());
        assertTrue(noKey.rows().isEmpty());

        var repeated = read(CsvFormat.COMMA_DOT, "record_id,temperature,Temperature\n1,21.37,21.38\n");
        assertTrue(repeated.summary().ambiguousHeader());
        assertTrue(repeated.rows().isEmpty());
    }

    @Test
    void countsDuplicateKeysAndSkipsUnusableRows() throws IOException {
        var reading = read(CsvFormat.COMMA_DOT, """
                record_id,temperature
                1,21.37
                1,21.38
                ,10.00
                abc,10.00
                2,"10.00
                3,10.00,extra

                4,10.00
                """);
        assertEquals(new SuspectSummary(8, 5, 1, true, false), reading.summary());
        assertEquals(3, reading.rows().size());
    }

    @Test
    void sizeLimitsStillApply() {
        var limits = new CsvLimits(CsvLimits.MAX_BYTES, CsvLimits.MAX_LINES, 64, 22, 500); // en-tête : 21 octets
        var refused = assertThrows(CsvRejectedException.class, () -> new SuspectCsvReader(SCHEMA, CsvFormat.COMMA_DOT,
                limits).read(new ByteArrayInputStream("record_id,temperature\n1,21.3700000000000000001\n"
                .getBytes(StandardCharsets.UTF_8)), row -> {}));
        assertEquals(Code.LINE_TOO_LONG, refused.code());
    }
}
