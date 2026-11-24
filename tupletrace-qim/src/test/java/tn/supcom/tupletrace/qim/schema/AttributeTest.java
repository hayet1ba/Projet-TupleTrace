package tn.supcom.tupletrace.qim.schema;

import org.junit.jupiter.api.Test;
import tn.supcom.tupletrace.qim.schema.SchemaException.Code;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static tn.supcom.tupletrace.qim.schema.AttributeType.*;
import static tn.supcom.tupletrace.qim.schema.Rejections.assertRejected;

class AttributeTest {
    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    void acceptsEligibleDecimalWhenStepIsWithinTolerance() {
        var temperature = Attribute.eligible(3, "temperature", decimal(5, 2), d("0.05"), d("0.01"));
        assertEquals(Role.ELIGIBLE, temperature.role());
        assertDoesNotThrow(() -> Attribute.eligible(3, "temperature", decimal(5, 2), d("0.05"), d("0.05")));
    }

    @Test
    void acceptsEligibleIntegerWithIntegralStepWhateverItsNotation() {
        assertDoesNotThrow(() -> Attribute.eligible(4, "count", integer(), d("2"), d("1")));
        assertDoesNotThrow(() -> Attribute.eligible(4, "count", integer(), d("2"), d("2.00")));
    }

    @Test
    void keyAndForbiddenAttributesCarryNoToleranceNorStep() {
        var key = Attribute.key(1, "record_id", integer());
        var forbidden = Attribute.forbidden(2, "sensor_id", category());
        assertEquals(0, key.tolerance().signum());
        assertNull(key.step());
        assertEquals(Role.INTERDIT, forbidden.role());
        assertDoesNotThrow(() -> new Attribute(1, "record_id", integer(), Role.CLE, d("0.00"), null));
    }

    @Test
    void refusesStepGreaterThanTolerance() {
        assertRejected(Code.STEP_EXCEEDS_TOLERANCE,
                () -> Attribute.eligible(3, "temperature", decimal(5, 2), d("0.01"), d("0.02")));
    }

    @Test
    void refusesMissingZeroOrNegativeStep() {
        for (var step : new BigDecimal[] {null, d("0"), d("-0.01")}) {
            assertRejected(Code.STEP_NOT_POSITIVE,
                    () -> Attribute.eligible(3, "temperature", decimal(5, 2), d("0.05"), step));
        }
    }

    @Test
    void refusesZeroOrNegativeToleranceOnEligibleColumn() {
        for (var tolerance : List.of(d("0"), d("-0.05"))) {
            assertRejected(Code.TOLERANCE_NOT_POSITIVE,
                    () -> Attribute.eligible(3, "temperature", decimal(5, 2), tolerance, d("0.01")));
        }
    }

    @Test
    void refusesStepFinerThanColumnResolution() {
        assertRejected(Code.STEP_FINER_THAN_RESOLUTION,
                () -> Attribute.eligible(3, "temperature", decimal(5, 2), d("0.05"), d("0.005")));
        assertRejected(Code.STEP_FINER_THAN_RESOLUTION,
                () -> Attribute.eligible(4, "count", integer(), d("2"), d("0.5")));
    }

    @Test
    void refusesStepOutsideColumnRange() {
        assertRejected(Code.STEP_OUT_OF_RANGE,
                () -> Attribute.eligible(3, "ratio", decimal(3, 2), d("100"), d("10")));
        assertRejected(Code.STEP_OUT_OF_RANGE,
                () -> Attribute.eligible(4, "count", integer(), d("1E30"), d("1E30")));
    }

    @Test
    void refusesEligibleRoleOnNonNumericTypes() {
        for (var type : List.of(text(), date(), category())) {
            assertRejected(Code.ELIGIBLE_NOT_NUMERIC,
                    () -> Attribute.eligible(5, "label", type, d("1"), d("1")));
        }
    }

    @Test
    void refusesToleranceOrStepOnKeyAndForbiddenColumns() {
        assertRejected(Code.TOLERANCE_ON_UNMARKABLE,
                () -> new Attribute(1, "record_id", integer(), Role.CLE, d("0.01"), null));
        assertRejected(Code.TOLERANCE_ON_UNMARKABLE,
                () -> new Attribute(2, "amount", decimal(10, 2), Role.INTERDIT, d("1"), null));
        assertRejected(Code.STEP_ON_UNMARKABLE,
                () -> new Attribute(2, "amount", decimal(10, 2), Role.INTERDIT, BigDecimal.ZERO, d("0.01")));
    }

    @Test
    void refusesInvalidDecimalSpecifications() {
        for (int[] spec : new int[][] {{0, 0}, {19, 2}, {5, 6}, {5, -1}}) {
            assertRejected(Code.INVALID_DECIMAL_SPEC, () -> decimal(spec[0], spec[1]));
        }
        assertEquals(999_999_999_999_999_999L, new DecimalType(18, 18).maxUnscaled());
        assertEquals(99_999L, new DecimalType(5, 2).maxUnscaled());
    }

    @Test
    void refusesNonPositiveAttributeId() {
        assertRejected(Code.INVALID_ATTRIBUTE_ID, () -> Attribute.key(0, "record_id", integer()));
        assertRejected(Code.INVALID_ATTRIBUTE_ID, () -> Attribute.key(-1, "record_id", integer()));
    }

    @Test
    void refusesBlankPaddedOrControlCharacterNames() {
        for (var name : List.of("", "   ", " temperature", "temperature ", "tempe\nrature", "temp\u0000")) {
            assertRejected(Code.INVALID_NAME, () -> Attribute.key(1, name, integer()));
        }
    }

    @Test
    void refusesMissingFields() {
        assertRejected(Code.MISSING_FIELD, () -> Attribute.key(1, null, integer()));
        assertRejected(Code.MISSING_FIELD, () -> Attribute.key(1, "record_id", null));
        assertRejected(Code.MISSING_FIELD, () -> new Attribute(1, "record_id", integer(), null, BigDecimal.ZERO, null));
        assertRejected(Code.MISSING_FIELD, () -> new Attribute(1, "record_id", integer(), Role.CLE, null, null));
    }

    @Test
    void normalizesNamesToNfc() {
        var decomposed = Attribute.forbidden(2, "été", text());
        assertEquals("été", decomposed.name());
    }
}
