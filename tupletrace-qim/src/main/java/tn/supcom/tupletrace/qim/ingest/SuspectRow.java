package tn.supcom.tupletrace.qim.ingest;

import tn.supcom.tupletrace.qim.canonical.CanonicalTuple;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * Ligne d'un extrait suspect, telle que le détecteur en a besoin : la clé (exacte) et les valeurs
 * des colonnes ELIGIBLE présentes et lisibles (éventuellement bruitées, donc sans borne de scale).
 *
 * @param key     tuple ne contenant que les colonnes CLE, suffisant pour l'identité canonique
 * @param numbers valeurs des colonnes ELIGIBLE lisibles, indexées par attributeId
 */
public record SuspectRow(CanonicalTuple key, Map<Integer, BigDecimal> numbers) {
    public SuspectRow {
        numbers = Map.copyOf(numbers);
    }

    public Optional<BigDecimal> number(int attributeId) {
        return Optional.ofNullable(numbers.get(attributeId));
    }
}
