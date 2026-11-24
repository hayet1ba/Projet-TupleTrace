package tn.supcom.tupletrace.qim.ingest;

import tn.supcom.tupletrace.qim.canonical.CanonicalTuple;
import tn.supcom.tupletrace.qim.schema.DatasetSchema;

import java.util.List;
import java.util.Objects;

/**
 * Lot de tuples canoniques validés (≤ 500), seule forme que le moteur acceptera (frontière F2).
 * Le constructeur est réservé au paquetage {@code ingest} : seul {@link TupleValidator} en
 * fabrique, donc un composant extérieur ne peut pas présenter un lot non validé.
 */
public final class ValidatedBatch {
    private final DatasetSchema schema;
    private final List<CanonicalTuple> tuples;

    ValidatedBatch(DatasetSchema schema, List<CanonicalTuple> tuples) {
        this.schema = Objects.requireNonNull(schema);
        this.tuples = List.copyOf(tuples);
    }

    public DatasetSchema schema() {
        return schema;
    }

    public List<CanonicalTuple> tuples() {
        return tuples;
    }

    public int size() {
        return tuples.size();
    }
}
