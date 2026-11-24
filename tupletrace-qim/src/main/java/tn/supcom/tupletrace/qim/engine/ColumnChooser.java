package tn.supcom.tupletrace.qim.engine;

import tn.supcom.tupletrace.qim.schema.Attribute;
import tn.supcom.tupletrace.qim.schema.DatasetSchema;

import java.util.List;

/**
 * Colonne qui porte le bit : HMAC-SHA-256(K_col, ser_tuple) mod ν, parmi les ν colonnes
 * ELIGIBLE triées par attribute_id (ordre canonique, indépendant de la position dans le fichier).
 */
final class ColumnChooser {
    private final KeyedPrf prf;
    private final List<Attribute> eligible;

    ColumnChooser(KeyedPrf prf, DatasetSchema schema) {
        this.prf = prf;
        this.eligible = schema.eligibleAttributes();
        if (eligible.isEmpty()) {
            throw new IllegalArgumentException("le schéma ne contient aucune colonne ELIGIBLE");
        }
    }

    Attribute choose(byte[] tupleIdentity) {
        return eligible.get(prf.mod(tupleIdentity, eligible.size()));
    }
}
