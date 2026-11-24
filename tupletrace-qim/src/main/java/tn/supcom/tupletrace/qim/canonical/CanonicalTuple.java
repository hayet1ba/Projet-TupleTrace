package tn.supcom.tupletrace.qim.canonical;

import tn.supcom.tupletrace.qim.canonical.CanonicalException.Code;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Tuple immuable dont les valeurs sont indexées par {@code attributeId}, jamais par position.
 * Deux tuples aux mêmes valeurs sont égaux quel que soit l'ordre dans lequel on les a remplis.
 */
public record CanonicalTuple(SortedMap<Integer, CanonicalValue> values) {

    public CanonicalTuple {
        if (values == null) {
            throw new CanonicalException(Code.MISSING_FIELD, "valeurs");
        }
        var copy = new TreeMap<Integer, CanonicalValue>();
        for (var entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new CanonicalException(Code.MISSING_FIELD, "cellule sans attribut ou sans valeur");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        values = Collections.unmodifiableSortedMap(copy);
    }

    public static CanonicalTuple of(Map<Integer, CanonicalValue> values) {
        if (values == null) {
            throw new CanonicalException(Code.MISSING_FIELD, "valeurs");
        }
        var sorted = new TreeMap<Integer, CanonicalValue>();
        for (var entry : values.entrySet()) {
            if (entry.getKey() == null) {
                throw new CanonicalException(Code.MISSING_FIELD, "cellule sans attribut");
            }
            sorted.put(entry.getKey(), entry.getValue());
        }
        return new CanonicalTuple(sorted);
    }

    public Optional<CanonicalValue> value(int attributeId) {
        return Optional.ofNullable(values.get(attributeId));
    }
}
