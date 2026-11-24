package tn.supcom.tupletrace.qim;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tn.supcom.tupletrace.qim.detect.DetectionResult;
import tn.supcom.tupletrace.qim.detect.Verdict;
import tn.supcom.tupletrace.qim.engine.MarkingKey;
import tn.supcom.tupletrace.qim.engine.MarkingParameters;
import tn.supcom.tupletrace.qim.engine.MarkingRefusedException;
import tn.supcom.tupletrace.qim.ingest.CsvFormat;
import tn.supcom.tupletrace.qim.ingest.CsvLimits;
import tn.supcom.tupletrace.qim.ingest.CsvRejectedException;
import tn.supcom.tupletrace.qim.schema.Attribute;
import tn.supcom.tupletrace.qim.schema.AttributeType;
import tn.supcom.tupletrace.qim.schema.DatasetSchema;
import tn.supcom.tupletrace.qim.schema.Role;
import tn.supcom.tupletrace.qim.testing.SyntheticSensors;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Démonstration de bout en bout (étape G) : importer un CSV → insérer le filigrane QIM → écrire la
 * copie marquée → extraire la marque d'un extrait remanié → rendre un verdict. Données entièrement
 * synthétiques, générées par le test dans un dossier temporaire (SR-PRV-02).
 */
class EndToEndTest {
    private static final int ROWS = 3_000;
    private static final TupleTraceEngine ENGINE = new TupleTraceEngine(
            SyntheticSensors.SCHEMA, CsvFormat.COMMA_DOT, CsvLimits.DEFAULT, MarkingParameters.DEFAULT);

    @TempDir
    Path folder;

    private MarkingKey key;

    @BeforeEach
    void generateKey() {
        key = MarkingKey.generate();           // jamais écrite : ni dans le code, ni sur disque
    }

    @AfterEach
    void eraseKey() {
        key.close();
    }

    private static DetectionResult detect(TupleTraceEngine engine, Path file, MarkingKey with) throws IOException {
        try (var in = Files.newInputStream(file)) {
            return engine.detect(in, with);
        }
    }

    private Path protectToFile(Path original) throws IOException {
        var copy = folder.resolve("copie-marquee.csv");
        try (var out = Files.newOutputStream(copy)) {
            ENGINE.protect(original, key, out);
        }
        return copy;
    }

    private static List<String> dataLines(String csv) {
        var lines = csv.split("\n");
        return new ArrayList<>(Arrays.asList(lines).subList(1, lines.length));
    }

    /** Mélange les lignes et réordonne les colonnes (ordre donné par indices de l'original). */
    private static String shuffle(String csv, long seed, int... columnOrder) {
        var lines = dataLines(csv);
        Collections.shuffle(lines, new Random(seed));
        lines.addFirst(csv.substring(0, csv.indexOf('\n')));
        return lines.stream().map(line -> {
            var f = line.split(",", -1);
            return Arrays.stream(columnOrder).mapToObj(i -> f[i]).collect(Collectors.joining(","));
        }).collect(Collectors.joining("\n", "", "\n"));
    }

    @Test
    void importMarkWriteShuffleDetect() throws IOException {
        // 1. Import : un CSV synthétique sur disque.
        var original = folder.resolve("mesures.csv");
        Files.writeString(original, SyntheticSensors.csv(ROWS, 2026), StandardCharsets.UTF_8);

        // 2-3. Filigrane QIM et copie marquée écrite sur disque.
        var copy = folder.resolve("copie-marquee.csv");
        TupleTraceEngine.Protection protection;
        try (var out = Files.newOutputStream(copy)) {
            protection = ENGINE.protect(original, key, out);
        }
        var quality = protection.quality();
        assertEquals(ROWS, quality.tuples());
        assertTrue(quality.markedCells() > 120, "cellules marquées : " + quality.markedCells());
        for (var column : quality.columns()) {
            var attribute = SyntheticSensors.SCHEMA.attribute(column.attributeId()).orElseThrow();
            assertTrue(column.maxAbsoluteError().compareTo(attribute.step()) <= 0, column.name());
            assertTrue(column.maxAbsoluteError().compareTo(attribute.tolerance()) <= 0, column.name());
        }
        assertEquals(64, protection.sourceSha256().length());

        // Les colonnes CLE et INTERDIT de la copie sont identiques à l'original, octet pour octet.
        var before = dataLines(Files.readString(original));
        var after = dataLines(Files.readString(copy));
        var attributes = SyntheticSensors.SCHEMA.attributes();
        for (int line = 0; line < ROWS; line++) {
            var b = before.get(line).split(",", -1);
            var a = after.get(line).split(",", -1);
            for (int column = 0; column < attributes.size(); column++) {
                if (attributes.get(column).role() != Role.ELIGIBLE) {
                    assertEquals(b[column], a[column]);
                }
            }
        }

        // 4. Un extrait remanié : lignes mélangées, colonnes dans un autre ordre.
        var suspect = folder.resolve("extrait-suspect.csv");
        Files.writeString(suspect, shuffle(Files.readString(copy), 7, 6, 3, 0, 5, 1, 4, 2));

        // 5. Verdict : la marque du propriétaire est reconnue.
        var verdict = detect(ENGINE, suspect, key);
        assertEquals(Verdict.POSITIF, verdict.verdict());
        assertEquals(verdict.bitsRead(), verdict.bitsMatching());
        assertTrue(verdict.pValue() < 1e-6);

        // L'original non marqué, lui, ne porte pas la marque.
        var unmarked = detect(ENGINE, original, key);
        assertEquals(Verdict.NEGATIF, unmarked.verdict());
    }

    @Test
    void anInvalidFileProducesNoCopyAtAll() throws IOException {
        var lines = dataLines(SyntheticSensors.csv(ROWS, 1));
        lines.set(ROWS - 1, lines.get(10));               // doublon de pk dans le DERNIER lot
        var original = folder.resolve("doublon.csv");
        Files.writeString(original, SyntheticSensors.HEADER + "\n" + String.join("\n", lines) + "\n");

        var copy = new ByteArrayOutputStream();
        var refused = assertThrows(CsvRejectedException.class, () -> ENGINE.protect(original, key, copy));
        assertEquals(CsvRejectedException.Code.DUPLICATE_KEY, refused.code());
        assertEquals(0, copy.size(), "aucun octet écrit : la passe 1 a tout arrêté");
    }

    @Test
    void aRefusedMarkingProducesNoCopyAtAll() throws IOException {
        var tight = new DatasetSchema(SyntheticSensors.SCHEMA.datasetId(), 1, List.of(
                Attribute.key(1, "record_id", AttributeType.integer()),
                Attribute.eligible(4, "temperature", AttributeType.decimal(5, 2),
                        new BigDecimal("0.05"), new BigDecimal("0.02"))));
        // Les 2 500 premières lignes (5 lots) se marquent sans problème ; les 500 dernières valent
        // 999,99 et ne peuvent pas recevoir le bit 0 sans sortir de DECIMAL(5,2) avec δ = 0,02.
        // Le refus n'arrive donc qu'au 6e lot, et pourtant rien ne doit avoir été écrit.
        var csv = new StringBuilder("record_id,temperature\n");
        for (int i = 0; i < ROWS; i++) {
            csv.append(i).append(i < 2_500 ? ",500.00\n" : ",999.99\n");
        }
        var original = folder.resolve("extreme.csv");
        Files.writeString(original, csv);
        var engine = new TupleTraceEngine(tight, CsvFormat.COMMA_DOT, CsvLimits.DEFAULT, MarkingParameters.DEFAULT);

        var copy = new ByteArrayOutputStream();
        var refused = assertThrows(MarkingRefusedException.class, () -> engine.protect(original, key, copy));
        assertEquals(MarkingRefusedException.Code.PRECISION_EXCEEDED, refused.code());
        assertEquals(0, copy.size(), "aucun octet écrit : la passe 1 a tout arrêté");
    }

    @Test
    void aSourceThatChangesBetweenThePassesIsRefused() {
        var first = SyntheticSensors.csv(500, 3);
        var second = first.replaceFirst("\n10,", "\n100000,");     // même format, autre contenu
        var opened = new AtomicInteger();
        TupleTraceEngine.Source source = () -> new ByteArrayInputStream(
                (opened.getAndIncrement() == 0 ? first : second).getBytes(StandardCharsets.UTF_8));

        var refused = assertThrows(MarkingRefusedException.class,
                () -> ENGINE.protect(source, key, new ByteArrayOutputStream()));
        assertEquals(MarkingRefusedException.Code.SOURCE_CHANGED, refused.code());
        assertEquals(2, opened.get());
    }

    @Test
    void aNoisyTwentyPercentExtractIsStillRecognized() throws IOException {
        int rows = 6_000;                      // 20 % = 1 200 lignes, soit environ 80 bits du filigrane
        var original = folder.resolve("mesures.csv");
        Files.writeString(original, SyntheticSensors.csv(rows, 4));
        var copy = protectToFile(original);

        var lines = dataLines(Files.readString(copy));
        Collections.shuffle(lines, new Random(8));
        var random = new Random(9);
        var extract = new StringBuilder(SyntheticSensors.HEADER).append('\n');
        for (var line : lines.subList(0, rows / 5)) {
            var f = line.split(",", -1);
            if (!f[3].isEmpty()) {
                f[3] = new BigDecimal(f[3]).add(BigDecimal.valueOf(random.nextInt(-4, 5), 3)).toPlainString();
            }
            extract.append(String.join(",", f)).append('\n');
        }
        var suspect = folder.resolve("extrait-20-pourcent.csv");
        Files.writeString(suspect, extract);

        var result = detect(ENGINE, suspect, key);
        assertEquals(Verdict.POSITIF, result.verdict(), "n = " + result.bitsRead());
        assertEquals(result.bitsRead(), result.bitsMatching(), "bruit < δ/2 : aucun bit perdu");
        assertEquals(rows / 5, result.extract().rowsRead());
    }

    @Test
    void worksWithSemicolonsAndDecimalCommas() throws IOException {
        var french = SyntheticSensors.csv(ROWS, 5).replace(',', ';').replace('.', ',');
        var original = folder.resolve("mesures-fr.csv");
        Files.writeString(original, french);
        var engine = new TupleTraceEngine(SyntheticSensors.SCHEMA, CsvFormat.SEMICOLON_COMMA,
                CsvLimits.DEFAULT, MarkingParameters.DEFAULT);

        var copy = folder.resolve("copie-fr.csv");
        try (var out = Files.newOutputStream(copy)) {
            engine.protect(original, key, out);
        }
        assertTrue(Files.readString(copy).lines().skip(1).allMatch(l -> l.split(";", -1).length == 7));
        assertFalse(Files.readString(copy).contains("."), "la virgule décimale est conservée");
        assertEquals(Verdict.POSITIF, detect(engine, copy, key).verdict());
    }

    @Test
    void anExtractWithoutTheKeyColumnIsIndeterminate() throws IOException {
        var original = folder.resolve("mesures.csv");
        Files.writeString(original, SyntheticSensors.csv(ROWS, 6));
        var copy = protectToFile(original);
        var withoutKey = folder.resolve("sans-cle.csv");
        Files.writeString(withoutKey, shuffle(Files.readString(copy), 1, 1, 2, 3, 4, 5, 6));

        var result = detect(ENGINE, withoutKey, key);
        assertEquals(Verdict.INDETERMINE, result.verdict());
        assertEquals(DetectionResult.Reason.MISSING_KEY_COLUMN, result.reason());
    }

    @Test
    void oneHundredThousandRows() throws IOException {
        var original = folder.resolve("cent-mille.csv");
        Files.writeString(original, SyntheticSensors.csv(100_000, 7));
        long start = System.nanoTime();
        var copy = protectToFile(original);
        long protectMillis = (System.nanoTime() - start) / 1_000_000;

        var result = detect(ENGINE, copy, key);
        assertEquals(Verdict.POSITIF, result.verdict());
        assertEquals(result.bitsRead(), result.bitsMatching());
        // Indication seulement : la mesure officielle (cible < 60 s sur 4 vCPU) se fait au jalon J2.
        System.out.printf("100 000 lignes protégées (2 passes) en %d ms ; n = %d, log10 p = %.0f%n",
                protectMillis, result.bitsRead(), result.log10PValue());
    }
}
