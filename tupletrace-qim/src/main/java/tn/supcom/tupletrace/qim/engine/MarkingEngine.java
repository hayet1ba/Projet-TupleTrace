package tn.supcom.tupletrace.qim.engine;

import tn.supcom.tupletrace.qim.canonical.CanonicalTuple;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue;
import tn.supcom.tupletrace.qim.engine.MarkingRefusedException.Code;
import tn.supcom.tupletrace.qim.ingest.ValidatedBatch;
import tn.supcom.tupletrace.qim.schema.DatasetSchema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Insère le filigrane dans des lots validés (frontière F2 : seul un {@link ValidatedBatch} entre).
 *
 * <p>Pour chaque tuple : s'il est sélectionné et dans la couche WATERMARK, la colonne choisie
 * reçoit le bit HMAC(K_wm, ser_tuple) par quantification. Les couches SEAL et FINGERPRINT sont
 * réservées et laissées intactes pour l'instant. Les colonnes CLE et INTERDIT ne sont jamais
 * lues pour être modifiées : seule une colonne ELIGIBLE peut changer.
 *
 * <p>Tout dépassement (précision du type ou tolérance) lève {@link MarkingRefusedException} :
 * le traitement entier doit alors être abandonné. Non thread-safe : une instance par traitement.
 */
public final class MarkingEngine {
    private final DatasetSchema schema;
    private final MarkingPlanner planner;
    private final Map<Integer, QimColumn> columns = new LinkedHashMap<>();
    private final QualityReport.Builder report;

    public MarkingEngine(MarkingKey key, DatasetSchema schema, MarkingParameters parameters) {
        this.schema = Objects.requireNonNull(schema, "schéma");
        if (schema.eligibleAttributes().isEmpty()) {
            throw new MarkingRefusedException(Code.NO_ELIGIBLE_COLUMN, 0);
        }
        this.planner = new MarkingPlanner(key, schema, parameters);
        schema.eligibleAttributes().forEach(a -> columns.put(a.attributeId(), new QimColumn(a)));
        this.report = new QualityReport.Builder(List.copyOf(columns.values()));
    }

    public MarkedBatch protect(ValidatedBatch batch) {
        Objects.requireNonNull(batch, "lot");
        if (!schema.equals(batch.schema())) {
            throw new MarkingRefusedException(Code.SCHEMA_MISMATCH, 0);
        }
        var marked = new ArrayList<CanonicalTuple>(batch.size());
        for (var tuple : batch.tuples()) {
            marked.add(mark(tuple));
        }
        return new MarkedBatch(schema, marked);
    }

    /** Rapport cumulé de tous les lots traités jusqu'ici. */
    public QualityReport report() {
        return report.build();
    }

    private CanonicalTuple mark(CanonicalTuple tuple) {
        report.tuple();
        int markedAttribute = 0;
        long markedUnits = 0;

        var placement = planner.placementOf(tuple);
        if (placement.isPresent()) {
            report.selected(placement.get().layer());
            if (placement.get().layer() == Layer.WATERMARK) {
                var column = columns.get(placement.get().column().attributeId());
                var before = column.units(valueOf(tuple, column));
                if (before == null) {
                    report.skippedNull();
                } else {
                    markedUnits = column.embed(before, placement.get().watermarkBit());
                    markedAttribute = column.attribute().attributeId();
                    report.marked(column, before, markedUnits);
                }
            }
        }

        for (var column : columns.values()) {
            var before = column.units(valueOf(tuple, column));
            if (before != null) {
                boolean isMarked = column.attribute().attributeId() == markedAttribute;
                report.observe(column, before, isMarked ? markedUnits : before);
            }
        }

        if (markedAttribute == 0) {
            return tuple;
        }
        var values = new HashMap<>(tuple.values());
        values.put(markedAttribute, columns.get(markedAttribute).value(markedUnits));
        return CanonicalTuple.of(values);
    }

    private static CanonicalValue valueOf(CanonicalTuple tuple, QimColumn column) {
        return tuple.value(column.attribute().attributeId()).orElse(CanonicalValue.nullValue());
    }
}
