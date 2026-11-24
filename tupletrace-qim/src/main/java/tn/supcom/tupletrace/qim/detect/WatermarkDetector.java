package tn.supcom.tupletrace.qim.detect;

import tn.supcom.tupletrace.qim.engine.Layer;
import tn.supcom.tupletrace.qim.engine.MarkingKey;
import tn.supcom.tupletrace.qim.engine.MarkingParameters;
import tn.supcom.tupletrace.qim.engine.MarkingPlanner;
import tn.supcom.tupletrace.qim.engine.QimColumn;
import tn.supcom.tupletrace.qim.ingest.CsvFormat;
import tn.supcom.tupletrace.qim.ingest.CsvLimits;
import tn.supcom.tupletrace.qim.ingest.SuspectCsvReader;
import tn.supcom.tupletrace.qim.schema.DatasetSchema;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Recherche le filigrane du propriétaire dans un extrait suspect. Pour chaque ligne de l'extrait,
 * on recalcule avec la clé si elle a été sélectionnée, dans quelle couche et quelle colonne, et le
 * bit attendu ; on relit la parité de la valeur et on compte les concordances. La décision repose
 * sur un test binomial (seuil 1e-6). Seul l'extrait entre : n, k et la p-valeur ne viennent
 * jamais de l'appelant (SR-INT-10).
 */
public final class WatermarkDetector {
    private final DatasetSchema schema;
    private final MarkingPlanner planner;
    private final Map<Integer, QimColumn> columns = new HashMap<>();

    public WatermarkDetector(MarkingKey key, DatasetSchema schema, MarkingParameters parameters) {
        this.schema = Objects.requireNonNull(schema, "schéma");
        this.planner = new MarkingPlanner(key, schema, parameters);
        schema.eligibleAttributes().forEach(a -> columns.put(a.attributeId(), new QimColumn(a)));
    }

    public DetectionResult detect(InputStream suspect, CsvFormat format, CsvLimits limits) throws IOException {
        long[] counts = new long[2];        // [0] = n bits lus, [1] = k bits concordants
        var summary = new SuspectCsvReader(schema, format, limits).read(suspect, row -> {
            var placement = planner.placementOf(row.key());
            if (placement.isEmpty() || placement.get().layer() != Layer.WATERMARK) {
                return;
            }
            var column = columns.get(placement.get().column().attributeId());
            row.number(column.attribute().attributeId()).ifPresent(value -> {
                counts[0]++;
                if (column.extract(value) == placement.get().watermarkBit()) {
                    counts[1]++;
                }
            });
        });
        return DetectionResult.decide(counts[0], counts[1], summary);
    }
}
