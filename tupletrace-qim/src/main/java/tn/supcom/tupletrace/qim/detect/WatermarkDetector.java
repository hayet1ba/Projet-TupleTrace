package tn.supcom.tupletrace.qim.detect;

import tn.supcom.tupletrace.qim.canonical.CanonicalSerializer;
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
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Recherche le filigrane du propriétaire dans un extrait suspect. Pour chaque ligne de l'extrait,
 * on recalcule avec la clé si elle a été sélectionnée, dans quelle couche et quelle colonne, et le
 * bit attendu ; on relit la parité de la valeur et on compte les concordances. La décision repose
 * sur un test binomial (seuil 1e-6). Seul l'extrait entre : n, k et la p-valeur ne viennent
 * jamais de l'appelant (SR-INT-10).
 *
 * <p>Une pk répétée dans l'extrait rend ses lignes ambiguës : toutes ses occurrences, première
 * comprise, sont écartées et comptées ; la décision se fait sur les lignes restantes. Une copie
 * retouchée d'une ligne ne peut donc pas injecter de faux bits.
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
        // Contribution de chaque ligne du filigrane, indexée par l'identité de sa pk :
        // vrai si le bit relu est celui attendu. Seules ces lignes (~7 %) sont gardées en mémoire.
        var contributions = new HashMap<ByteBuffer, Boolean>();
        var summary = new SuspectCsvReader(schema, format, limits).read(suspect, row -> {
            var identity = CanonicalSerializer.serializeTuple(schema, row.key());
            if (row.repeatedKey()) {
                contributions.remove(ByteBuffer.wrap(identity));
                return;
            }
            var placement = planner.placementOf(identity);
            if (placement.isEmpty() || placement.get().layer() != Layer.WATERMARK) {
                return;
            }
            var column = columns.get(placement.get().column().attributeId());
            row.number(column.attribute().attributeId()).ifPresent(value -> contributions.put(
                    ByteBuffer.wrap(identity), column.extract(value) == placement.get().watermarkBit()));
        });
        long n = contributions.size();
        long k = contributions.values().stream().filter(Boolean::booleanValue).count();
        return DetectionResult.decide(n, k, summary);
    }
}
