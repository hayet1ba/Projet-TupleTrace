package tn.supcom.tupletrace.qim.ingest;

import org.junit.jupiter.api.Test;
import tn.supcom.tupletrace.qim.ingest.CsvRejectedException.Code;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CsvLineParserTest {
    private static List<String> parse(String line) {
        return CsvLineParser.parse(line, ',', 64, 2);
    }

    private static void assertRejected(Code expected, String line) {
        var refused = assertThrows(CsvRejectedException.class, () -> parse(line));
        assertEquals(expected, refused.code());
        assertEquals(2, refused.lineNumber());
    }

    @Test
    void splitsOnTheDeclaredSeparatorOnly() {
        assertEquals(List.of("1001", "S17", "21.37"), parse("1001,S17,21.37"));
        assertEquals(List.of("1001", "21,37"), CsvLineParser.parse("1001;21,37", ';', 64, 2));
    }

    @Test
    void handlesQuotedFieldsAsInRfc4180() {
        assertEquals(List.of("a,b", "say \"hi\"", "x"), parse("\"a,b\",\"say \"\"hi\"\"\",x"));
    }

    @Test
    void distinguishesAbsentValueFromEmptyString() {
        assertEquals(Arrays.asList("1", null, "", null), parse("1,,\"\","));
        assertEquals(Arrays.asList((String) null), parse(""));
    }

    @Test
    void refusesQuotesInTheMiddleOfAFieldOrTextAfterTheClosingQuote() {
        assertRejected(Code.MALFORMED_QUOTES, "ab\"c,d");
        assertRejected(Code.MALFORMED_QUOTES, "\"ab\"c,d");
    }

    @Test
    void refusesFieldsSpanningSeveralLines() {
        assertRejected(Code.UNTERMINATED_QUOTE, "1,\"début d'un champ");
        assertRejected(Code.UNTERMINATED_QUOTE, "\"");
    }

    @Test
    void refusesMoreThanTheColumnLimitAndAcceptsExactlyIt() {
        var sixtyFour = String.join(",", java.util.Collections.nCopies(64, "x"));
        assertEquals(64, parse(sixtyFour).size());
        assertRejected(Code.TOO_MANY_COLUMNS, sixtyFour + ",x");
        assertRejected(Code.TOO_MANY_COLUMNS, sixtyFour + ",");
    }
}
