package tn.supcom.tupletrace.qim.ingest;

import org.junit.jupiter.api.Test;
import tn.supcom.tupletrace.qim.ingest.CsvRejectedException.Code;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BoundedLineReaderTest {
    private static List<String> readAll(byte[] bytes, CsvLimits limits) throws IOException {
        var reader = new BoundedLineReader(new ByteArrayInputStream(bytes), limits);
        var lines = new ArrayList<String>();
        String line;
        while ((line = reader.readLine()) != null) {
            lines.add(line);
        }
        return lines;
    }

    private static List<String> readAll(String text) throws IOException {
        return readAll(text.getBytes(StandardCharsets.UTF_8), CsvLimits.DEFAULT);
    }

    private static CsvRejectedException rejected(byte[] bytes, CsvLimits limits) {
        return assertThrows(CsvRejectedException.class, () -> readAll(bytes, limits));
    }

    @Test
    void acceptsLfCrlfAndAMissingFinalNewline() throws IOException {
        assertEquals(List.of("a", "b", "c"), readAll("a\nb\r\nc"));
        assertEquals(List.of("a", "b"), readAll("a\r\nb\r\n"));
        assertEquals(List.of(), readAll(""));
        assertEquals(List.of(""), readAll("\n"));
    }

    @Test
    void ignoresTheUtf8ByteOrderMarkOnTheFirstLineOnly() throws IOException {
        var bom = "﻿";
        assertEquals(List.of("record_id", bom + "x"), readAll(bom + "record_id\n" + bom + "x"));
    }

    @Test
    void refusesInvalidUtf8() {
        var bytes = new byte[] {'o', 'k', '\n', (byte) 0xC3, (byte) 0x28, '\n'};
        var refused = rejected(bytes, CsvLimits.DEFAULT);
        assertEquals(Code.INVALID_UTF8, refused.code());
        assertEquals(2, refused.lineNumber());
    }

    @Test
    void lineLimitCountsBytesNotCharacters() throws IOException {
        int max = CsvLimits.MAX_LINE_BYTES;
        assertEquals(max, readAll("a".repeat(max) + "\n").getFirst().length());
        assertEquals(max, readAll("a".repeat(max) + "\r\n").getFirst().length());
        assertEquals(max / 2, readAll("é".repeat(max / 2)).getFirst().length());

        var tooLong = rejected(("ok\n" + "a".repeat(max + 1) + "\n").getBytes(StandardCharsets.UTF_8),
                CsvLimits.DEFAULT);
        assertEquals(Code.LINE_TOO_LONG, tooLong.code());
        assertEquals(2, tooLong.lineNumber());
        assertEquals(Code.LINE_TOO_LONG,
                rejected(("é".repeat(max / 2) + "a").getBytes(StandardCharsets.UTF_8), CsvLimits.DEFAULT).code());
    }

    @Test
    void fileLimitIsExactInBytes() throws IOException {
        var limits = new CsvLimits(10, 100, 64, 100, 500);
        assertEquals(List.of("1234", "5678"), readAll("1234\n5678\n".getBytes(StandardCharsets.UTF_8), limits));
        assertEquals(Code.FILE_TOO_LARGE, rejected("1234\n5678\n9".getBytes(StandardCharsets.UTF_8), limits).code());
    }
}
