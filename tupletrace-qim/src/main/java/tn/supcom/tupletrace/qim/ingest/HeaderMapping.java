package tn.supcom.tupletrace.qim.ingest;

import tn.supcom.tupletrace.qim.ingest.CsvRejectedException.Code;
import tn.supcom.tupletrace.qim.schema.Attribute;
import tn.supcom.tupletrace.qim.schema.DatasetSchema;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Associe chaque position de colonne du fichier à un attribut du schéma, par son nom.
 * C'est le seul endroit où la position d'une colonne intervient : ensuite, tout est indexé par
 * {@code attributeId}. Les noms sont comparés après normalisation NFC, sans les espaces
 * entourants et sans tenir compte de la casse (le schéma garantit déjà leur unicité ainsi).
 */
final class HeaderMapping {
    private final Attribute[] byPosition;

    private HeaderMapping(Attribute[] byPosition) {
        this.byPosition = byPosition;
    }

    /** À l'import : l'en-tête doit contenir exactement les colonnes du schéma, dans n'importe quel ordre. */
    static HeaderMapping strict(List<String> header, DatasetSchema schema) {
        var attributesByName = new HashMap<String, Attribute>();
        for (var attribute : schema.attributes()) {
            attributesByName.put(key(attribute.name()), attribute);
        }
        var seen = new HashSet<String>();
        var byPosition = new Attribute[header.size()];
        for (int position = 0; position < header.size(); position++) {
            var name = header.get(position);
            if (name == null || name.isBlank()) {
                throw new CsvRejectedException(Code.EMPTY_HEADER, 1);
            }
            var key = key(name);
            if (!seen.add(key)) {
                throw new CsvRejectedException(Code.DUPLICATE_HEADER, 1);
            }
            var attribute = attributesByName.get(key);
            if (attribute == null) {
                throw new CsvRejectedException(Code.UNKNOWN_COLUMN, 1);
            }
            byPosition[position] = attribute;
        }
        if (seen.size() != attributesByName.size()) {
            throw new CsvRejectedException(Code.MISSING_COLUMN, 1);
        }
        return new HeaderMapping(byPosition);
    }

    int columnCount() {
        return byPosition.length;
    }

    Attribute attributeAt(int position) {
        return byPosition[position];
    }

    private static String key(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFC).strip().toLowerCase(Locale.ROOT);
    }
}
