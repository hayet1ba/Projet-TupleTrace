package tn.supcom.tupletrace.qim.export;

import org.junit.jupiter.api.Test;
import tn.supcom.tupletrace.qim.canonical.CanonicalTuple;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.*;
import tn.supcom.tupletrace.qim.ingest.CsvFormat;
import tn.supcom.tupletrace.qim.ingest.CsvLimits;
import tn.supcom.tupletrace.qim.ingest.CsvStreamReader;
import tn.supcom.tupletrace.qim.schema.Attribute;
import tn.supcom.tupletrace.qim.schema.AttributeType;
import tn.supcom.tupletrace.qim.schema.DatasetSchema;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CsvWriterTest {
    private static final UUID DATASET = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final DatasetSchema SCHEMA = new DatasetSchema(DATASET, 1, List.of(
            Attribute.forbidden(7, "note", AttributeType.text()),
            Attribute.eligible(4, "temperature", AttributeType.decimal(5, 2), new BigDecimal("0.05"), new BigDecimal("0.01")),
            Attribute.key(1, "record_id", AttributeType.integer()),
            Attribute.forbidden(3, "measured_on", AttributeType.date()),
            Attribute.forbidden(2, "sensor_id", AttributeType.category())));

    private static CanonicalTuple tuple(long id, CanonicalValue temperature, CanonicalValue note) {
        return CanonicalTuple.of(Map.of(1, new IntegerValue(id), 2, new CategoryValue("S17"),
                3, DateValue.of(LocalDate.of(2026, 11, 24)), 4, temperature, 7, note));
    }

    private static String write(CsvFormat format, List<CanonicalTuple> tuples) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var writer = new CsvWriter(bytes, SCHEMA, format);
        writer.writeAll(tuples);
        writer.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static List<CanonicalTuple> read(CsvFormat format, String csv) throws IOException {
        var tuples = new ArrayList<CanonicalTuple>();
        new CsvStreamReader(SCHEMA, format, CsvLimits.DEFAULT).read(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), batch -> tuples.addAll(batch.tuples()));
        return tuples;
    }

    @Test
    void writesColumnsInAttributeIdOrderWithTheDeclaredSeparators() throws IOException {
        var rows = List.of(tuple(1001, new DecimalValue(2137, 2), new TextValue("ok")),
                tuple(-7, new DecimalValue(-5, 2), new TextValue("ok")));
        assertEquals("""
                record_id,sensor_id,measured_on,temperature,note
                1001,S17,2026-11-24,21.37,ok
                -7,S17,2026-11-24,-0.05,ok
                """, write(CsvFormat.COMMA_DOT, rows));
        assertEquals("""
                record_id;sensor_id;measured_on;temperature;note
                1001;S17;2026-11-24;21,37;ok
                -7;S17;2026-11-24;-0,05;ok
                """, write(CsvFormat.SEMICOLON_COMMA, rows));
    }

    @Test
    void keepsTheScaleOfDecimals() throws IOException {
        var csv = write(CsvFormat.COMMA_DOT, List.of(tuple(1, new DecimalValue(0, 2), new TextValue("x")),
                tuple(2, new DecimalValue(2100, 2), new TextValue("x"))));
        assertTrue(csv.contains(",0.00,"));
        assertTrue(csv.contains(",21.00,"));
    }

    @Test
    void absentValueAndEmptyTextStayDistinct() throws IOException {
        var rows = List.of(tuple(1, CanonicalValue.nullValue(), CanonicalValue.nullValue()),
                tuple(2, new DecimalValue(1, 2), new TextValue("")));
        var csv = write(CsvFormat.COMMA_DOT, rows);
        assertTrue(csv.contains("\n1,S17,2026-11-24,,\n"));
        assertTrue(csv.contains("\n2,S17,2026-11-24,0.01,\"\"\n"));
        assertEquals(rows, read(CsvFormat.COMMA_DOT, csv));
    }

    @Test
    void quotesOnlyWhenNeededAndRoundTripsExactly() throws IOException {
        var rows = List.of(tuple(1, new DecimalValue(1, 2), new TextValue("a, b")),
                tuple(2, new DecimalValue(1, 2), new TextValue("say \"hi\"")),
                tuple(3, new DecimalValue(1, 2), new TextValue("a; b")),
                tuple(4, new DecimalValue(1, 2), new TextValue("  espaces  ")),
                tuple(5, new DecimalValue(1, 2), new TextValue("été")));
        var commaFile = write(CsvFormat.COMMA_DOT, rows);
        assertTrue(commaFile.contains("\"a, b\""));
        assertTrue(commaFile.contains("\"say \"\"hi\"\"\""));
        assertTrue(commaFile.contains(",a; b\n"));
        assertEquals(rows, read(CsvFormat.COMMA_DOT, commaFile));

        var semicolonFile = write(CsvFormat.SEMICOLON_COMMA, rows);
        assertTrue(semicolonFile.contains(";a, b\n"));
        assertTrue(semicolonFile.contains("\"a; b\""));
        assertEquals(rows, read(CsvFormat.SEMICOLON_COMMA, semicolonFile));
    }

    @Test
    void readingThenWritingAPermutedFileGivesTheCanonicalColumnOrder() throws IOException {
        var permuted = "note,temperature,sensor_id,record_id,measured_on\nok,21.37,S17,1001,2026-11-24\n";
        assertEquals("record_id,sensor_id,measured_on,temperature,note\n1001,S17,2026-11-24,21.37,ok\n",
                write(CsvFormat.COMMA_DOT, read(CsvFormat.COMMA_DOT, permuted)));
    }

    @Test
    void writesOnlyTheHeaderForAnEmptyDataset() throws IOException {
        assertEquals("record_id,sensor_id,measured_on,temperature,note\n", write(CsvFormat.COMMA_DOT, List.of()));
    }

    @Test
    void refusesANewlineInsideAField() {
        var rows = List.of(tuple(1, new DecimalValue(1, 2), new TextValue("ligne 1\nligne 2")));
        assertThrows(IllegalArgumentException.class, () -> write(CsvFormat.COMMA_DOT, rows));
    }
}
