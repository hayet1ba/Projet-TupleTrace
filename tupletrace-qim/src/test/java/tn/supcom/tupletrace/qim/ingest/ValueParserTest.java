package tn.supcom.tupletrace.qim.ingest;

import org.junit.jupiter.api.Test;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.*;
import tn.supcom.tupletrace.qim.ingest.CsvRejectedException.Code;
import tn.supcom.tupletrace.qim.schema.AttributeType;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ValueParserTest {
    private static final AttributeType DECIMAL_5_2 = AttributeType.decimal(5, 2);
    private static final ValueParser DOT = new ValueParser(CsvFormat.COMMA_DOT);
    private static final ValueParser COMMA = new ValueParser(CsvFormat.SEMICOLON_COMMA);

    private static void assertRejected(Code expected, ValueParser parser, String raw, AttributeType type) {
        var refused = assertThrows(CsvRejectedException.class, () -> parser.parse(raw, type, 7));
        assertEquals(expected, refused.code(), raw);
        assertEquals(7, refused.lineNumber());
    }

    @Test
    void decimalCommaAndDecimalDotGiveTheSameValue() {
        var withComma = COMMA.parse("21,37", DECIMAL_5_2, 2);
        var withDot = DOT.parse("21.37", DECIMAL_5_2, 2);
        assertEquals(new DecimalValue(2137, 2), withComma);
        assertEquals(withDot, withComma);
        assertEquals(new DecimalValue(2100, 2), DOT.parse("21", DECIMAL_5_2, 2));
        assertEquals(new DecimalValue(-5, 2), COMMA.parse("-0,05", DECIMAL_5_2, 2));
    }

    @Test
    void refusesNumbersThatCouldBeReadInTwoWays() {
        assertRejected(Code.AMBIGUOUS_VALUE, DOT, "1,234", DECIMAL_5_2);
        assertRejected(Code.AMBIGUOUS_VALUE, COMMA, "1.234", DECIMAL_5_2);
        assertRejected(Code.AMBIGUOUS_VALUE, DOT, "1.234.5", DECIMAL_5_2);
        assertRejected(Code.AMBIGUOUS_VALUE, COMMA, "1.234,5", DECIMAL_5_2);
        assertRejected(Code.AMBIGUOUS_VALUE, DOT, "1,234", AttributeType.integer());
        assertRejected(Code.AMBIGUOUS_VALUE, DOT, "1.0", AttributeType.integer());
    }

    @Test
    void refusesMalformedNumbers() {
        for (var raw : List.of("abc", "1e5", " 21.37", "21.37 ", ".5", "5.", "+5", "", "--1", "0x1F")) {
            assertRejected(Code.INVALID_VALUE, DOT, raw, DECIMAL_5_2);
        }
        for (var raw : List.of("abc", "", " 12", "+12", "1e3")) {
            assertRejected(Code.INVALID_VALUE, DOT, raw, AttributeType.integer());
        }
    }

    @Test
    void refusesValuesOutsideTheDeclaredType() {
        assertRejected(Code.OUT_OF_RANGE, DOT, "21.375", DECIMAL_5_2);
        assertRejected(Code.OUT_OF_RANGE, DOT, "1000.00", DECIMAL_5_2);
        assertRejected(Code.OUT_OF_RANGE, DOT, "9223372036854775808", AttributeType.integer());
        assertEquals(new IntegerValue(Long.MIN_VALUE), DOT.parse("-9223372036854775808", AttributeType.integer(), 2));
        assertEquals(new DecimalValue(99_999, 2), DOT.parse("999.99", DECIMAL_5_2, 2));
    }

    @Test
    void acceptsOnlyRealIsoDates() {
        assertEquals(DateValue.of(LocalDate.of(2026, 11, 24)), DOT.parse("2026-11-24", AttributeType.date(), 2));
        for (var raw : List.of("2026-02-30", "26-11-24", "2026/11/24", "24-11-2026", "+2026-11-24", "2026-11-24T00:00")) {
            assertRejected(Code.INVALID_VALUE, DOT, raw, AttributeType.date());
        }
    }

    @Test
    void keepsTextAndCategoriesExactlyAndMapsEmptyFieldsToNull() {
        assertEquals(new TextValue("  avec espaces, et =formule"), DOT.parse("  avec espaces, et =formule",
                AttributeType.text(), 2));
        assertEquals(new CategoryValue(""), DOT.parse("", AttributeType.category(), 2));
        for (var type : List.of(AttributeType.integer(), DECIMAL_5_2, AttributeType.text(),
                AttributeType.date(), AttributeType.category())) {
            assertEquals(CanonicalValue.nullValue(), DOT.parse(null, type, 2));
        }
    }
}
