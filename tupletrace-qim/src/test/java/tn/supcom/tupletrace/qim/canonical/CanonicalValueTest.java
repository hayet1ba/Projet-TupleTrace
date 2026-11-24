package tn.supcom.tupletrace.qim.canonical;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import tn.supcom.tupletrace.qim.canonical.CanonicalException.Code;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.*;
import tn.supcom.tupletrace.qim.schema.AttributeType;
import tn.supcom.tupletrace.qim.schema.AttributeType.DecimalType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalValueTest {
    private static final DecimalType DECIMAL_5_2 = new DecimalType(5, 2);

    static void assertRejected(Code expected, Executable construction) {
        var refused = assertThrows(CanonicalException.class, construction);
        assertEquals(expected, refused.code());
    }

    private static DecimalValue decimal(String number) {
        return DecimalValue.of(new BigDecimal(number), DECIMAL_5_2);
    }

    @Test
    void decimalsWithTrailingZerosHaveOneCanonicalValue() {
        assertEquals(new DecimalValue(2137, 2), decimal("21.37"));
        assertEquals(decimal("21.37"), decimal("21.370"));
        assertEquals(decimal("21.37"), decimal("21.3700"));
        assertEquals(decimal("0"), decimal("-0.00"));
        assertEquals(new BigDecimal("21.37"), decimal("21.37").toBigDecimal());
    }

    @Test
    void decimalsAreNeverRoundedSilently() {
        assertRejected(Code.DECIMAL_SCALE, () -> decimal("21.375"));
        assertRejected(Code.DECIMAL_PRECISION, () -> decimal("1000.00"));
        assertRejected(Code.DECIMAL_PRECISION, () -> decimal("-1000"));
        assertEquals(new DecimalValue(99_999, 2), decimal("999.99"));
        assertEquals(new DecimalValue(-99_999, 2), decimal("-999.99"));
        assertRejected(Code.DECIMAL_SCALE, () -> new DecimalValue(1, -1));
        assertRejected(Code.MISSING_FIELD, () -> DecimalValue.of(null, DECIMAL_5_2));
    }

    @Test
    void textAndCategoriesAreNormalizedToNfc() {
        assertEquals(new TextValue("été"), new TextValue("été"));
        assertEquals(new CategoryValue("café"), new CategoryValue("café"));
        assertArrayEquals(CanonicalSerializer.encodeField(new TextValue("é")),
                CanonicalSerializer.encodeField(new TextValue("é")));
    }

    @Test
    void refusesTextThatCannotBeEncodedInUtf8() {
        assertRejected(Code.MALFORMED_TEXT, () -> new TextValue("abc\uD800"));
        assertRejected(Code.MALFORMED_TEXT, () -> new CategoryValue("\uDC00"));
        assertRejected(Code.MISSING_FIELD, () -> new TextValue(null));
    }

    @Test
    void nullEmptyTextAndTypesAreAllDistinguished() {
        var encodings = List.of(
                CanonicalSerializer.encodeField(CanonicalValue.nullValue()),
                CanonicalSerializer.encodeField(new TextValue("")),
                CanonicalSerializer.encodeField(new CategoryValue("")),
                CanonicalSerializer.encodeField(new TextValue("12")),
                CanonicalSerializer.encodeField(new CategoryValue("12")),
                CanonicalSerializer.encodeField(new IntegerValue(12)),
                CanonicalSerializer.encodeField(new DecimalValue(12, 0)),
                CanonicalSerializer.encodeField(new DecimalValue(1200, 2)),
                CanonicalSerializer.encodeField(new DateValue(12)));
        for (int i = 0; i < encodings.size(); i++) {
            for (int j = i + 1; j < encodings.size(); j++) {
                assertFalse(java.util.Arrays.equals(encodings.get(i), encodings.get(j)), i + " / " + j);
            }
        }
    }

    @Test
    void datesCountDaysSinceEpoch() {
        assertEquals(0, DateValue.of(LocalDate.of(1970, 1, 1)).epochDay());
        assertEquals(-1, DateValue.of(LocalDate.of(1969, 12, 31)).epochDay());
        assertRejected(Code.MISSING_FIELD, () -> DateValue.of(null));
    }

    @Test
    void checksCompatibilityWithDeclaredType() {
        assertTrue(CanonicalValue.fits(AttributeType.integer(), new IntegerValue(-5)));
        assertTrue(CanonicalValue.fits(DECIMAL_5_2, new DecimalValue(-99_999, 2)));
        assertTrue(CanonicalValue.fits(AttributeType.text(), new TextValue("x")));
        assertTrue(CanonicalValue.fits(AttributeType.date(), new DateValue(1)));
        assertTrue(CanonicalValue.fits(AttributeType.category(), new CategoryValue("S17")));
        for (var type : List.of(AttributeType.integer(), DECIMAL_5_2, AttributeType.text(),
                AttributeType.date(), AttributeType.category())) {
            assertTrue(CanonicalValue.fits(type, CanonicalValue.nullValue()));
        }

        assertFalse(CanonicalValue.fits(AttributeType.integer(), new TextValue("12")));
        assertFalse(CanonicalValue.fits(AttributeType.text(), new CategoryValue("12")));
        assertFalse(CanonicalValue.fits(DECIMAL_5_2, new DecimalValue(2137, 3)));
        assertFalse(CanonicalValue.fits(DECIMAL_5_2, new DecimalValue(100_000, 2)));
        assertFalse(CanonicalValue.fits(DECIMAL_5_2, new DecimalValue(Long.MIN_VALUE, 2)));
        assertFalse(CanonicalValue.fits(DECIMAL_5_2, new IntegerValue(12)));
    }

    @Test
    void tuplesAreIndexedByAttributeIdWhateverTheFillingOrder() {
        var forward = new LinkedHashMap<Integer, CanonicalValue>();
        forward.put(1, new IntegerValue(1001));
        forward.put(4, decimal("21.37"));
        var backward = new LinkedHashMap<Integer, CanonicalValue>();
        backward.put(4, decimal("21.37"));
        backward.put(1, new IntegerValue(1001));

        assertEquals(CanonicalTuple.of(forward), CanonicalTuple.of(backward));
        assertEquals(List.of(1, 4), List.copyOf(CanonicalTuple.of(backward).values().keySet()));
    }

    @Test
    void tuplesAreImmutable() {
        var source = new LinkedHashMap<Integer, CanonicalValue>(Map.of(1, new IntegerValue(1001)));
        var tuple = CanonicalTuple.of(source);
        source.put(2, new IntegerValue(7));
        assertTrue(tuple.value(2).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> tuple.values().put(3, new IntegerValue(1)));
    }

    @Test
    void refusesCellsWithoutAttributeOrValue() {
        var withNullValue = new LinkedHashMap<Integer, CanonicalValue>();
        withNullValue.put(1, null);
        assertRejected(Code.MISSING_FIELD, () -> CanonicalTuple.of(withNullValue));

        var withNullKey = new LinkedHashMap<Integer, CanonicalValue>();
        withNullKey.put(null, new IntegerValue(1));
        assertRejected(Code.MISSING_FIELD, () -> CanonicalTuple.of(withNullKey));
        assertRejected(Code.MISSING_FIELD, () -> CanonicalTuple.of(null));
    }
}
