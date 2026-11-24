package tn.supcom.tupletrace.qim.engine;

import org.junit.jupiter.api.Test;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.DecimalValue;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.IntegerValue;
import tn.supcom.tupletrace.qim.engine.MarkingRefusedException.Code;
import tn.supcom.tupletrace.qim.schema.Attribute;
import tn.supcom.tupletrace.qim.schema.AttributeType;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class QimColumnTest {
    private static QimColumn decimalColumn(String tolerance, String step) {
        return new QimColumn(Attribute.eligible(4, "temperature", AttributeType.decimal(5, 2),
                new BigDecimal(tolerance), new BigDecimal(step)));
    }

    private static void assertRefused(Code expected, Runnable embedding) {
        var refused = assertThrows(MarkingRefusedException.class, embedding::run);
        assertEquals(expected, refused.code());
        assertEquals(4, refused.attributeId());
    }

    @Test
    void refusesWhenTheMarkWouldExceedThePrecisionOfTheType() {
        var column = decimalColumn("0.05", "0.02");
        // 999,99 et bit 0 : 1000,00 dépasse DECIMAL(5,2), 999,96 est à 0,03 > δ = 0,02.
        assertRefused(Code.PRECISION_EXCEEDED, () -> column.embed(99_999, 0));
        assertRefused(Code.PRECISION_EXCEEDED, () -> column.embed(-99_999, 0));
        assertEquals(99_998, column.embed(99_999, 1));
        assertEquals(-99_998, column.embed(-99_999, 1));
    }

    @Test
    void staysInsideThePrecisionWhenAnEquallyClosePointExists() {
        var column = decimalColumn("0.05", "0.01");
        assertEquals(99_998, column.embed(99_999, 0));
        assertEquals(-99_998, column.embed(-99_999, 0));
    }

    @Test
    void integerColumnsUseTheFullLongRangeExceptItsAsymmetricMinimum() {
        var count = new QimColumn(Attribute.eligible(6, "count", AttributeType.integer(),
                new BigDecimal("2"), new BigDecimal("1")));
        assertEquals(6, count.embed(5, 0));
        assertEquals(Long.MAX_VALUE - 1, count.embed(Long.MAX_VALUE, 0));
        var refused = assertThrows(MarkingRefusedException.class, () -> count.embed(Long.MIN_VALUE, 0));
        assertEquals(Code.PRECISION_EXCEEDED, refused.code());
    }

    @Test
    void readsOnlyValuesOfTheDeclaredTypeAndScale() {
        var column = decimalColumn("0.05", "0.01");
        assertEquals(2137L, column.units(new DecimalValue(2137, 2)));
        assertNull(column.units(CanonicalValue.nullValue()));
        assertRefused(Code.TYPE_MISMATCH, () -> column.units(new IntegerValue(2137)));
        assertRefused(Code.TYPE_MISMATCH, () -> column.units(new DecimalValue(21370, 3)));
        assertEquals(new DecimalValue(2138, 2), column.value(2138));
    }

    @Test
    void acceptsOnlyEligibleNumericColumns() {
        assertThrows(IllegalArgumentException.class,
                () -> new QimColumn(Attribute.key(1, "record_id", AttributeType.integer())));
        assertThrows(IllegalArgumentException.class,
                () -> new QimColumn(Attribute.forbidden(2, "amount", AttributeType.decimal(10, 2))));
    }
}
