package tn.supcom.tupletrace.qim.ingest;

import tn.supcom.tupletrace.qim.canonical.CanonicalException;
import tn.supcom.tupletrace.qim.canonical.CanonicalSerializer;
import tn.supcom.tupletrace.qim.canonical.CanonicalTuple;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue;
import tn.supcom.tupletrace.qim.ingest.CsvRejectedException.Code;
import tn.supcom.tupletrace.qim.schema.DatasetSchema;
import tn.supcom.tupletrace.qim.schema.Role;

import java.util.HashMap;
import java.util.List;

/**
 * Transforme les champs d'une ligne en tuple canonique et vérifie pk non nulle et unique sur
 * tout le jeu. C'est le seul constructeur de {@link ValidatedBatch}.
 */
final class TupleValidator {
    private final DatasetSchema schema;
    private final HeaderMapping mapping;
    private final ValueParser parser;
    private final PkUniquenessIndex keys = new PkUniquenessIndex();

    TupleValidator(DatasetSchema schema, HeaderMapping mapping, ValueParser parser) {
        this.schema = schema;
        this.mapping = mapping;
        this.parser = parser;
    }

    CanonicalTuple validate(List<String> fields, long lineNumber) {
        if (fields.size() != mapping.columnCount()) {
            throw new CsvRejectedException(Code.FIELD_COUNT_MISMATCH, lineNumber);
        }
        var values = new HashMap<Integer, CanonicalValue>();
        for (int position = 0; position < fields.size(); position++) {
            var attribute = mapping.attributeAt(position);
            var value = parser.parse(fields.get(position), attribute.type(), lineNumber);
            if (attribute.role() == Role.CLE && value instanceof CanonicalValue.NullValue) {
                throw new CsvRejectedException(Code.NULL_KEY, lineNumber);
            }
            values.put(attribute.attributeId(), value);
        }
        var tuple = CanonicalTuple.of(values);
        byte[] identity;
        try {
            identity = CanonicalSerializer.serializeTuple(schema, tuple);
        } catch (CanonicalException refused) {
            throw new CsvRejectedException(Code.INVALID_VALUE, lineNumber);
        }
        if (!keys.add(identity)) {
            throw new CsvRejectedException(Code.DUPLICATE_KEY, lineNumber);
        }
        return tuple;
    }

    ValidatedBatch seal(List<CanonicalTuple> tuples) {
        return new ValidatedBatch(schema, tuples);
    }
}
