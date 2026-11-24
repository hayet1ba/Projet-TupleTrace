package tn.supcom.tupletrace.qim.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tn.supcom.tupletrace.qim.canonical.CanonicalSerializer;
import tn.supcom.tupletrace.qim.canonical.CanonicalTuple;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.DecimalValue;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.IntegerValue;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.TextValue;
import tn.supcom.tupletrace.qim.engine.MarkingPlanner.Placement;
import tn.supcom.tupletrace.qim.ingest.CsvFormat;
import tn.supcom.tupletrace.qim.ingest.CsvLimits;
import tn.supcom.tupletrace.qim.ingest.CsvStreamReader;
import tn.supcom.tupletrace.qim.schema.Attribute;
import tn.supcom.tupletrace.qim.schema.AttributeType;
import tn.supcom.tupletrace.qim.schema.DatasetSchema;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** Sélection des lignes, répartition en couches et choix de la colonne (étape D). */
class SelectionTest {
    private static final UUID DATASET = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final int N = 100_000;

    private static final Attribute RECORD_ID = Attribute.key(1, "record_id", AttributeType.integer());
    private static final Attribute NOTE = Attribute.forbidden(2, "note", AttributeType.text());
    private static final Attribute TEMPERATURE = Attribute.eligible(4, "temperature", AttributeType.decimal(5, 2),
            new BigDecimal("0.05"), new BigDecimal("0.01"));
    private static final Attribute HUMIDITY = Attribute.eligible(5, "humidity", AttributeType.decimal(5, 2),
            new BigDecimal("0.10"), new BigDecimal("0.02"));
    private static final Attribute COUNT = Attribute.eligible(7, "count", AttributeType.integer(),
            new BigDecimal("2"), new BigDecimal("1"));
    private static final DatasetSchema SCHEMA =
            new DatasetSchema(DATASET, 1, List.of(RECORD_ID, NOTE, TEMPERATURE, HUMIDITY, COUNT));

    private MarkingKey key;

    @BeforeEach
    void generateKey() {
        key = MarkingKey.generate();      // clé de test générée à chaque exécution, jamais écrite
    }

    @AfterEach
    void eraseKey() {
        key.close();
    }

    private static CanonicalTuple tuple(long recordId, long temperatureHundredths) {
        return CanonicalTuple.of(Map.of(
                1, new IntegerValue(recordId),
                2, new TextValue("ok"),
                4, new DecimalValue(temperatureHundredths, 2),
                5, new DecimalValue(5000, 2),
                7, new IntegerValue(3)));
    }

    private static byte[] identity(long recordId) {
        return CanonicalSerializer.serializeTuple(SCHEMA, tuple(recordId, 0));
    }

    private LineSelector selector(MarkingKey with, int gamma) {
        return new LineSelector(new KeyedPrf(with.subkey(KeyUsage.SELECTION, DATASET, 1)), new MarkingParameters(gamma));
    }

    private static int countSelected(LineSelector selector) {
        return (int) IntStream.range(0, N).filter(i -> selector.isSelected(identity(i))).count();
    }

    @Test
    void selectsTwentyPercentOfOneHundredThousandKeysByDefault() {
        assertEquals(5, MarkingParameters.DEFAULT.gamma());
        int selected = countSelected(selector(key, MarkingParameters.DEFAULT.gamma()));
        assertTrue(selected >= 19_000 && selected <= 21_000, "sélectionnées : " + selected);
    }

    @Test
    void gammaControlsTheFraction() {
        int tenPercent = countSelected(selector(key, 10));
        assertTrue(tenPercent >= 9_000 && tenPercent <= 11_000, "γ = 10 : " + tenPercent);
        int halfOfThem = countSelected(selector(key, 2));
        assertTrue(halfOfThem >= 49_000 && halfOfThem <= 51_000, "γ = 2 : " + halfOfThem);
    }

    @Test
    void sameKeyAndSamePkAlwaysGiveTheSameDecision() {
        var first = selector(key, 5);
        var second = selector(key, 5);
        for (int i = 0; i < 10_000; i++) {
            assertEquals(first.isSelected(identity(i)), second.isSelected(identity(i)));
        }
    }

    @Test
    void anotherKeySelectsOtherLines() {
        try (var other = MarkingKey.generate()) {
            var mine = selector(key, 5);
            var theirs = selector(other, 5);
            long disagreements = IntStream.range(0, 10_000)
                    .filter(i -> mine.isSelected(identity(i)) != theirs.isSelected(identity(i))).count();
            // Indépendance attendue : désaccord ≈ 2 × 0,2 × 0,8 = 32 %.
            assertTrue(disagreements > 2_500, "désaccords : " + disagreements);
        }
    }

    @Test
    void refusesGammaOutsideTwoToTwenty() {
        assertThrows(IllegalArgumentException.class, () -> new MarkingParameters(1));
        assertThrows(IllegalArgumentException.class, () -> new MarkingParameters(21));
        assertDoesNotThrow(() -> new MarkingParameters(2));
        assertDoesNotThrow(() -> new MarkingParameters(20));
    }

    @Test
    void selectedLinesAreSplitIntoThreeLayersOfAboutOneThird() {
        var planner = new MarkingPlanner(key, SCHEMA, MarkingParameters.DEFAULT);
        var perLayer = new EnumMap<Layer, Integer>(Layer.class);
        int selected = 0;
        for (int i = 0; i < N; i++) {
            var placement = planner.placementOf(tuple(i, 0));
            if (placement.isPresent()) {
                selected++;
                perLayer.merge(placement.get().layer(), 1, Integer::sum);
            }
        }
        for (var layer : Layer.values()) {
            double share = perLayer.getOrDefault(layer, 0) / (double) selected;
            assertTrue(Math.abs(share - 1.0 / 3) < 0.02, layer + " : " + share);
        }
    }

    @Test
    void columnIsAlwaysAnEligibleOneAndAllAreUsed() {
        var chooser = new ColumnChooser(new KeyedPrf(key.subkey(KeyUsage.COLUMN, DATASET, 1)), SCHEMA);
        var perColumn = new HashMap<Attribute, Integer>();
        for (int i = 0; i < 30_000; i++) {
            perColumn.merge(chooser.choose(identity(i)), 1, Integer::sum);
        }
        assertEquals(Map.of(TEMPERATURE, 0, HUMIDITY, 0, COUNT, 0).keySet(), perColumn.keySet());
        perColumn.values().forEach(count -> assertTrue(Math.abs(count - 10_000) < 600, "répartition : " + perColumn));
    }

    @Test
    void columnChoiceFollowsAttributeIdNotDeclarationOrder() {
        var reversed = new DatasetSchema(DATASET, 1, List.of(COUNT, HUMIDITY, TEMPERATURE, NOTE, RECORD_ID));
        var declared = new MarkingPlanner(key, SCHEMA, MarkingParameters.DEFAULT);
        var shuffled = new MarkingPlanner(key, reversed, MarkingParameters.DEFAULT);
        for (int i = 0; i < 5_000; i++) {
            assertEquals(declared.placementOf(tuple(i, 0)), shuffled.placementOf(tuple(i, 0)));
        }
    }

    @Test
    void refusesASchemaWithoutEligibleColumn() {
        var noEligible = new DatasetSchema(DATASET, 1, List.of(RECORD_ID, NOTE));
        assertThrows(IllegalArgumentException.class, () -> new MarkingPlanner(key, noEligible, MarkingParameters.DEFAULT));
    }

    @Test
    void decisionIgnoresNonKeyValuesSoMarkingCannotMoveIt() {
        var planner = new MarkingPlanner(key, SCHEMA, MarkingParameters.DEFAULT);
        for (int i = 0; i < 5_000; i++) {
            assertEquals(planner.placementOf(tuple(i, 2137)), planner.placementOf(tuple(i, 2138)));
        }
    }

    @Test
    void decisionDoesNotDependOnRowOrColumnOrderInTheFile() throws IOException {
        var rows = IntStream.range(0, 3_000).mapToObj(i -> new String[] {
                String.valueOf(i), "ok", (i % 90) + "." + String.format("%02d", i % 100), "50.00", "3"}).toList();
        var original = new StringBuilder("record_id,note,temperature,humidity,count\n");
        rows.forEach(r -> original.append(String.join(",", r)).append('\n'));

        var shuffledRows = new ArrayList<>(rows);
        Collections.shuffle(shuffledRows, new Random(42));
        var permuted = new StringBuilder("count,humidity,record_id,temperature,note\n");
        shuffledRows.forEach(r -> permuted.append(String.join(",", r[4], r[3], r[0], r[2], r[1])).append('\n'));

        assertEquals(placements(original.toString()), placements(permuted.toString()));
    }

    private Map<IntegerValue, Optional<Placement>> placements(String csv) throws IOException {
        var planner = new MarkingPlanner(key, SCHEMA, MarkingParameters.DEFAULT);
        var result = new HashMap<IntegerValue, Optional<Placement>>();
        new CsvStreamReader(SCHEMA, CsvFormat.COMMA_DOT, CsvLimits.DEFAULT)
                .read(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), batch ->
                        batch.tuples().forEach(t -> result.put((IntegerValue) t.value(1).orElseThrow(),
                                planner.placementOf(t))));
        assertEquals(3_000, result.size());
        return result;
    }
}
