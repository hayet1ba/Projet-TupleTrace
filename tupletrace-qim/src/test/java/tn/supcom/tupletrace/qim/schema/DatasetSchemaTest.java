package tn.supcom.tupletrace.qim.schema;

import org.junit.jupiter.api.Test;
import tn.supcom.tupletrace.qim.schema.SchemaException.Code;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static tn.supcom.tupletrace.qim.schema.AttributeType.*;
import static tn.supcom.tupletrace.qim.schema.Rejections.assertRejected;

class DatasetSchemaTest {
    private static final UUID DATASET = UUID.fromString("00000000-0000-4000-8000-000000000001");

    private static final Attribute RECORD_ID = Attribute.key(1, "record_id", integer());
    private static final Attribute SENSOR_ID = Attribute.forbidden(2, "sensor_id", category());
    private static final Attribute MEASURED_ON = Attribute.forbidden(3, "measured_on", date());
    private static final Attribute TEMPERATURE = Attribute.eligible(4, "temperature", decimal(5, 2),
            new BigDecimal("0.05"), new BigDecimal("0.01"));
    private static final Attribute HUMIDITY = Attribute.eligible(5, "humidity", decimal(5, 2),
            new BigDecimal("0.10"), new BigDecimal("0.02"));

    private static DatasetSchema schema(Attribute... attributes) {
        return new DatasetSchema(DATASET, 1, Arrays.asList(attributes));
    }

    @Test
    void exposesKeyAndEligibleColumnsInCanonicalOrder() {
        var schema = schema(HUMIDITY, MEASURED_ON, RECORD_ID, TEMPERATURE, SENSOR_ID);
        assertEquals(List.of(RECORD_ID, SENSOR_ID, MEASURED_ON, TEMPERATURE, HUMIDITY), schema.attributes());
        assertEquals(List.of(RECORD_ID), schema.keyAttributes());
        assertEquals(List.of(TEMPERATURE, HUMIDITY), schema.eligibleAttributes());
    }

    @Test
    void declarationOrderNeverChangesTheSchema() {
        var declared = schema(RECORD_ID, SENSOR_ID, MEASURED_ON, TEMPERATURE, HUMIDITY);
        var shuffled = schema(TEMPERATURE, SENSOR_ID, HUMIDITY, RECORD_ID, MEASURED_ON);
        assertEquals(declared, shuffled);
        assertEquals(declared.eligibleAttributes(), shuffled.eligibleAttributes());
    }

    @Test
    void acceptsCompositeKeysOrderedByAttributeId() {
        var site = Attribute.key(7, "site", category());
        var schema = schema(site, TEMPERATURE, RECORD_ID);
        assertEquals(List.of(RECORD_ID, site), schema.keyAttributes());
    }

    @Test
    void findsAttributesByIdOnly() {
        var schema = schema(RECORD_ID, TEMPERATURE);
        assertEquals(TEMPERATURE, schema.attribute(4).orElseThrow());
        assertTrue(schema.attribute(99).isEmpty());
    }

    @Test
    void isImmutableEvenIfTheSourceListChanges() {
        var source = new ArrayList<>(List.of(RECORD_ID, TEMPERATURE));
        var schema = new DatasetSchema(DATASET, 1, source);
        source.add(HUMIDITY);
        assertEquals(2, schema.attributes().size());
        assertThrows(UnsupportedOperationException.class, () -> schema.attributes().add(HUMIDITY));
    }

    @Test
    void refusesSchemaWithoutKeyColumn() {
        assertRejected(Code.MISSING_KEY, () -> schema(SENSOR_ID, TEMPERATURE));
    }

    @Test
    void refusesDuplicateAttributeIds() {
        var otherWithSameId = Attribute.forbidden(4, "station", category());
        assertRejected(Code.DUPLICATE_ATTRIBUTE_ID, () -> schema(RECORD_ID, TEMPERATURE, otherWithSameId));
    }

    @Test
    void refusesDuplicateNamesIgnoringCaseAndUnicodeForm() {
        var upperCase = Attribute.forbidden(8, "Temperature", text());
        assertRejected(Code.DUPLICATE_NAME, () -> schema(RECORD_ID, TEMPERATURE, upperCase));

        var composed = Attribute.forbidden(8, "été", text());
        var decomposed = Attribute.forbidden(9, "été", text());
        assertRejected(Code.DUPLICATE_NAME, () -> schema(RECORD_ID, composed, decomposed));
    }

    @Test
    void acceptsUpToSixtyFourAttributesAndRefusesMore() {
        var attributes = new ArrayList<Attribute>();
        attributes.add(RECORD_ID);
        IntStream.rangeClosed(2, 64).forEach(i -> attributes.add(Attribute.forbidden(i, "c" + i, text())));
        assertEquals(64, new DatasetSchema(DATASET, 1, attributes).attributes().size());

        attributes.add(Attribute.forbidden(65, "c65", text()));
        assertRejected(Code.TOO_MANY_ATTRIBUTES, () -> new DatasetSchema(DATASET, 1, attributes));
    }

    @Test
    void refusesEmptyOrMissingAttributeList() {
        assertRejected(Code.NO_ATTRIBUTE, () -> new DatasetSchema(DATASET, 1, List.of()));
        assertRejected(Code.NO_ATTRIBUTE, () -> new DatasetSchema(DATASET, 1, null));
        assertRejected(Code.MISSING_FIELD, () -> schema(RECORD_ID, null));
    }

    @Test
    void refusesMissingDatasetIdAndNonPositiveVersion() {
        assertRejected(Code.MISSING_FIELD, () -> new DatasetSchema(null, 1, List.of(RECORD_ID)));
        assertRejected(Code.INVALID_VERSION, () -> new DatasetSchema(DATASET, 0, List.of(RECORD_ID)));
    }
}
