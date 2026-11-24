package tn.supcom.tupletrace.qim.testing;

import tn.supcom.tupletrace.qim.engine.MarkingEngine;
import tn.supcom.tupletrace.qim.engine.MarkingKey;
import tn.supcom.tupletrace.qim.engine.MarkingParameters;
import tn.supcom.tupletrace.qim.export.CsvWriter;
import tn.supcom.tupletrace.qim.ingest.CsvFormat;
import tn.supcom.tupletrace.qim.ingest.CsvLimits;
import tn.supcom.tupletrace.qim.ingest.CsvStreamReader;
import tn.supcom.tupletrace.qim.schema.Attribute;
import tn.supcom.tupletrace.qim.schema.AttributeType;
import tn.supcom.tupletrace.qim.schema.DatasetSchema;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.UUID;

/**
 * Jeu de mesures de capteurs entièrement synthétique, généré avec une graine fixe (SR-PRV-02) :
 * aucune donnée réelle n'est utilisée ni versionnée. Les valeurs sont déjà en forme canonique,
 * sans virgule ni guillemet dans les champs, pour pouvoir manipuler les lignes en texte brut.
 */
public final class SyntheticSensors {
    public static final DatasetSchema SCHEMA = new DatasetSchema(
            UUID.fromString("00000000-0000-4000-8000-000000000001"), 1, java.util.List.of(
            Attribute.key(1, "record_id", AttributeType.integer()),
            Attribute.forbidden(2, "sensor_id", AttributeType.category()),
            Attribute.forbidden(3, "measured_on", AttributeType.date()),
            Attribute.eligible(4, "temperature", AttributeType.decimal(5, 2), new BigDecimal("0.05"), new BigDecimal("0.01")),
            Attribute.eligible(5, "humidity", AttributeType.decimal(5, 2), new BigDecimal("0.10"), new BigDecimal("0.02")),
            Attribute.eligible(6, "count", AttributeType.integer(), new BigDecimal("2"), new BigDecimal("1")),
            Attribute.forbidden(7, "note", AttributeType.text())));

    public static final String HEADER = "record_id,sensor_id,measured_on,temperature,humidity,count,note";

    private SyntheticSensors() {}

    /** Une cellule de température sur 97 est vide, pour exercer les valeurs absentes. */
    public static String csv(int rows, long seed) {
        var random = new Random(seed);
        var csv = new StringBuilder(HEADER).append('\n');
        for (int i = 0; i < rows; i++) {
            var temperature = i % 97 == 0 ? "" : BigDecimal.valueOf(random.nextInt(-4_000, 6_001), 2).toPlainString();
            csv.append(i).append(",S").append(random.nextInt(20))
                    .append(",2026-").append(String.format("%02d-%02d", 1 + random.nextInt(12), 1 + random.nextInt(28)))
                    .append(',').append(temperature)
                    .append(',').append(BigDecimal.valueOf(random.nextInt(0, 10_001), 2).toPlainString())
                    .append(',').append(random.nextInt(-50, 501))
                    .append(",note-").append(i).append('\n');
        }
        return csv.toString();
    }

    /** Copie marquée par le moteur : lecture stricte, filigrane, écriture canonique. */
    public static String markedCopy(MarkingKey key, String csv) throws IOException {
        var engine = new MarkingEngine(key, SCHEMA, MarkingParameters.DEFAULT);
        var bytes = new ByteArrayOutputStream();
        var writer = new CsvWriter(bytes, SCHEMA, CsvFormat.COMMA_DOT);
        IOException[] failure = new IOException[1];
        new CsvStreamReader(SCHEMA, CsvFormat.COMMA_DOT, CsvLimits.DEFAULT).read(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), batch -> {
                    try {
                        writer.writeAll(engine.protect(batch).tuples());
                    } catch (IOException e) {
                        failure[0] = e;
                    }
                });
        if (failure[0] != null) {
            throw failure[0];
        }
        writer.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
