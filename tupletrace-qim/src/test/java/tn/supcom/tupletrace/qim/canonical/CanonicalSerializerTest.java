package tn.supcom.tupletrace.qim.canonical;

import org.junit.jupiter.api.Test;
import tn.supcom.tupletrace.qim.canonical.CanonicalException.Code;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.*;
import tn.supcom.tupletrace.qim.schema.Attribute;
import tn.supcom.tupletrace.qim.schema.AttributeType;
import tn.supcom.tupletrace.qim.schema.DatasetSchema;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static tn.supcom.tupletrace.qim.canonical.CanonicalValueTest.assertRejected;

class CanonicalSerializerTest {
    private static final UUID DATASET = UUID.fromString("00000000-0000-4000-8000-000000000001");

    private static final Attribute RECORD_ID = Attribute.key(1, "record_id", AttributeType.integer());
    private static final Attribute TEMPERATURE = Attribute.eligible(4, "temperature", AttributeType.decimal(5, 2),
            new BigDecimal("0.05"), new BigDecimal("0.01"));
    private static final DatasetSchema SENSORS = new DatasetSchema(DATASET, 1, List.of(RECORD_ID, TEMPERATURE));

    private static CanonicalTuple sensor(long recordId, long temperatureHundredths) {
        return CanonicalTuple.of(Map.of(1, new IntegerValue(recordId), 4, new DecimalValue(temperatureHundredths, 2)));
    }

    private static DatasetSchema compositeKey(AttributeType type) {
        return new DatasetSchema(DATASET, 1, List.of(
                Attribute.key(1, "part_a", type), Attribute.key(2, "part_b", type)));
    }

    private static CanonicalTuple pair(CanonicalValue a, CanonicalValue b) {
        return CanonicalTuple.of(Map.of(1, a, 2, b));
    }

    @Test
    void producesTheDocumentedTypeLengthValueLayout() {
        var hex = HexFormat.of();
        var expectedTuple = "54544944" + "01"                       // "TTID" || version_format
                + "07" + "00000010" + "0000000000004000" + "8000000000000001"   // UUID id_jeu
                + "01" + "00000004" + "00000001"                    // INT32 version_schema
                + "02" + "00000008" + "00000000000003e9";            // INTEGER pk = 1001
        assertEquals(expectedTuple, hex.formatHex(CanonicalSerializer.serializeTuple(SENSORS, sensor(1001, 2137))));
        assertEquals(expectedTuple + "01" + "00000004" + "00000004", // INT32 attribute_id = 4
                hex.formatHex(CanonicalSerializer.serializeCell(SENSORS, sensor(1001, 2137), 4)));
    }

    @Test
    void compositeKeys12And34DifferFrom1And234() {
        var text = compositeKey(AttributeType.text());
        assertFalse(Arrays.equals(
                CanonicalSerializer.serializeTuple(text, pair(new TextValue("12"), new TextValue("34"))),
                CanonicalSerializer.serializeTuple(text, pair(new TextValue("1"), new TextValue("234")))));

        var category = compositeKey(AttributeType.category());
        assertFalse(Arrays.equals(
                CanonicalSerializer.serializeTuple(category, pair(new CategoryValue("12"), new CategoryValue("34"))),
                CanonicalSerializer.serializeTuple(category, pair(new CategoryValue("1"), new CategoryValue("234")))));

        var integer = compositeKey(AttributeType.integer());
        assertFalse(Arrays.equals(
                CanonicalSerializer.serializeTuple(integer, pair(new IntegerValue(12), new IntegerValue(34))),
                CanonicalSerializer.serializeTuple(integer, pair(new IntegerValue(1), new IntegerValue(234)))));
    }

    @Test
    void swappingTheTwoPartsOfAKeyGivesAnotherIdentity() {
        var text = compositeKey(AttributeType.text());
        assertFalse(Arrays.equals(
                CanonicalSerializer.serializeTuple(text, pair(new TextValue("12"), new TextValue("34"))),
                CanonicalSerializer.serializeTuple(text, pair(new TextValue("34"), new TextValue("12")))));
    }

    @Test
    void identityNeverDependsOnColumnOrRowOrder() {
        var reversedDeclaration = new DatasetSchema(DATASET, 1, List.of(TEMPERATURE, RECORD_ID));

        var columnsForward = new LinkedHashMap<Integer, CanonicalValue>();
        columnsForward.put(1, new IntegerValue(1001));
        columnsForward.put(4, new DecimalValue(2137, 2));
        var columnsBackward = new LinkedHashMap<Integer, CanonicalValue>();
        columnsBackward.put(4, new DecimalValue(2137, 2));
        columnsBackward.put(1, new IntegerValue(1001));

        var reference = CanonicalSerializer.serializeCell(SENSORS, CanonicalTuple.of(columnsForward), 4);
        assertArrayEquals(reference, CanonicalSerializer.serializeCell(SENSORS, CanonicalTuple.of(columnsBackward), 4));
        assertArrayEquals(reference,
                CanonicalSerializer.serializeCell(reversedDeclaration, CanonicalTuple.of(columnsBackward), 4));

        // L'ordre des lignes n'intervient pas : l'identité ne dépend que du tuple lui-même.
        var rows = List.of(sensor(1001, 2137), sensor(1002, 1999), sensor(1003, 2500));
        var before = rows.stream().map(t -> CanonicalSerializer.serializeTuple(SENSORS, t)).toList();
        var after = rows.reversed().stream().map(t -> CanonicalSerializer.serializeTuple(SENSORS, t)).toList();
        for (int i = 0; i < rows.size(); i++) {
            assertArrayEquals(before.get(i), after.get(rows.size() - 1 - i));
        }
    }

    @Test
    void compositeKeyOrderFollowsAttributeIdNotDeclaration() {
        var declared = new DatasetSchema(DATASET, 1, List.of(
                Attribute.key(1, "part_a", AttributeType.text()), Attribute.key(2, "part_b", AttributeType.text())));
        var reversed = new DatasetSchema(DATASET, 1, List.of(
                Attribute.key(2, "part_b", AttributeType.text()), Attribute.key(1, "part_a", AttributeType.text())));
        var tuple = pair(new TextValue("12"), new TextValue("34"));
        assertArrayEquals(CanonicalSerializer.serializeTuple(declared, tuple),
                CanonicalSerializer.serializeTuple(reversed, tuple));
    }

    @Test
    void identityIgnoresNonKeyValuesSoMarkingCannotChangeIt() {
        assertArrayEquals(CanonicalSerializer.serializeTuple(SENSORS, sensor(1001, 2137)),
                CanonicalSerializer.serializeTuple(SENSORS, sensor(1001, 2138)));
        assertArrayEquals(CanonicalSerializer.serializeCell(SENSORS, sensor(1001, 2137), 4),
                CanonicalSerializer.serializeCell(SENSORS, sensor(1001, 2138), 4));
    }

    @Test
    void datasetVersionKeyAndAttributeAllChangeTheIdentity() {
        var reference = CanonicalSerializer.serializeCell(SENSORS, sensor(1001, 2137), 4);
        var otherDataset = new DatasetSchema(UUID.fromString("00000000-0000-4000-8000-000000000002"), 1,
                List.of(RECORD_ID, TEMPERATURE));
        var otherVersion = new DatasetSchema(DATASET, 2, List.of(RECORD_ID, TEMPERATURE));

        assertFalse(Arrays.equals(reference, CanonicalSerializer.serializeCell(otherDataset, sensor(1001, 2137), 4)));
        assertFalse(Arrays.equals(reference, CanonicalSerializer.serializeCell(otherVersion, sensor(1001, 2137), 4)));
        assertFalse(Arrays.equals(reference, CanonicalSerializer.serializeCell(SENSORS, sensor(1002, 2137), 4)));
        assertFalse(Arrays.equals(reference, CanonicalSerializer.serializeCell(SENSORS, sensor(1001, 2137), 1)));
    }

    @Test
    void refusesNullMissingOrMistypedKeys() {
        assertRejected(Code.NULL_KEY, () -> CanonicalSerializer.serializeTuple(SENSORS,
                CanonicalTuple.of(Map.of(1, CanonicalValue.nullValue(), 4, new DecimalValue(2137, 2)))));
        assertRejected(Code.MISSING_VALUE, () -> CanonicalSerializer.serializeTuple(SENSORS,
                CanonicalTuple.of(Map.of(4, new DecimalValue(2137, 2)))));
        assertRejected(Code.TYPE_MISMATCH, () -> CanonicalSerializer.serializeTuple(SENSORS,
                CanonicalTuple.of(Map.of(1, new TextValue("1001")))));

        var decimalKey = new DatasetSchema(DATASET, 1, List.of(Attribute.key(1, "code", AttributeType.decimal(5, 2))));
        assertRejected(Code.TYPE_MISMATCH, () -> CanonicalSerializer.serializeTuple(decimalKey,
                CanonicalTuple.of(Map.of(1, new DecimalValue(2137, 3)))));
    }

    @Test
    void refusesUnknownAttributeAndMissingArguments() {
        assertRejected(Code.UNKNOWN_ATTRIBUTE, () -> CanonicalSerializer.serializeCell(SENSORS, sensor(1001, 2137), 99));
        assertRejected(Code.MISSING_FIELD, () -> CanonicalSerializer.serializeTuple(null, sensor(1001, 2137)));
        assertRejected(Code.MISSING_FIELD, () -> CanonicalSerializer.serializeTuple(SENSORS, null));
        assertRejected(Code.MISSING_FIELD, () -> CanonicalSerializer.serializeCell(null, sensor(1001, 2137), 4));
        assertRejected(Code.MISSING_FIELD, () -> CanonicalSerializer.encodeField(null));
    }
}
