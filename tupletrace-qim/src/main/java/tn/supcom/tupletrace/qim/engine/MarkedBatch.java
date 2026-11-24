package tn.supcom.tupletrace.qim.engine;

import tn.supcom.tupletrace.qim.canonical.CanonicalTuple;
import tn.supcom.tupletrace.qim.schema.DatasetSchema;

import java.util.List;
import java.util.Objects;

/** Lot sorti du moteur. Seul {@link MarkingEngine} en fabrique (constructeur réservé au paquetage). */
public final class MarkedBatch {
    private final DatasetSchema schema;
    private final List<CanonicalTuple> tuples;

    MarkedBatch(DatasetSchema schema, List<CanonicalTuple> tuples) {
        this.schema = Objects.requireNonNull(schema);
        this.tuples = List.copyOf(tuples);
    }

    public DatasetSchema schema() {
        return schema;
    }

    public List<CanonicalTuple> tuples() {
        return tuples;
    }
}
