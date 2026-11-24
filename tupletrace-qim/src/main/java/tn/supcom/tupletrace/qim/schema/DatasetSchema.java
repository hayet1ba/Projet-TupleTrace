package tn.supcom.tupletrace.qim.schema;

import tn.supcom.tupletrace.qim.schema.SchemaException.Code;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Schéma logique versionné d'un jeu. Les attributs sont toujours conservés triés par
 * {@code attributeId} : l'ordre de déclaration ou la position des colonnes dans un fichier
 * n'a aucune influence.
 */
public record DatasetSchema(UUID datasetId, int version, List<Attribute> attributes) {
    public static final int MAX_ATTRIBUTES = 64;

    public DatasetSchema {
        if (datasetId == null) {
            throw new SchemaException(Code.MISSING_FIELD, "id_jeu");
        }
        if (version < 1) {
            throw new SchemaException(Code.INVALID_VERSION, "version " + version);
        }
        if (attributes == null || attributes.isEmpty()) {
            throw new SchemaException(Code.NO_ATTRIBUTE, "schéma vide");
        }
        if (attributes.size() > MAX_ATTRIBUTES) {
            throw new SchemaException(Code.TOO_MANY_ATTRIBUTES, attributes.size() + " attributs");
        }
        var sorted = new ArrayList<Attribute>(attributes.size());
        for (var attribute : attributes) {
            if (attribute == null) {
                throw new SchemaException(Code.MISSING_FIELD, "attribut nul");
            }
            sorted.add(attribute);
        }
        sorted.sort(Comparator.comparingInt(Attribute::attributeId));

        var ids = new HashSet<Integer>();
        var names = new HashSet<String>();
        boolean hasKey = false;
        for (var attribute : sorted) {
            if (!ids.add(attribute.attributeId())) {
                throw new SchemaException(Code.DUPLICATE_ATTRIBUTE_ID, "attribut " + attribute.attributeId());
            }
            // « Temperature » et « temperature » désigneraient la même colonne pour un humain.
            if (!names.add(attribute.name().toLowerCase(Locale.ROOT))) {
                throw new SchemaException(Code.DUPLICATE_NAME, "attribut " + attribute.attributeId());
            }
            hasKey |= attribute.role() == Role.CLE;
        }
        if (!hasKey) {
            throw new SchemaException(Code.MISSING_KEY, "aucune colonne CLE");
        }
        attributes = List.copyOf(sorted);
    }

    /** Colonnes de la clé stable, triées par attributeId (pk éventuellement composée). */
    public List<Attribute> keyAttributes() {
        return withRole(Role.CLE);
    }

    /** Colonnes qui peuvent porter un bit, triées par attributeId (ordre canonique). */
    public List<Attribute> eligibleAttributes() {
        return withRole(Role.ELIGIBLE);
    }

    public Optional<Attribute> attribute(int attributeId) {
        return attributes.stream().filter(a -> a.attributeId() == attributeId).findFirst();
    }

    private List<Attribute> withRole(Role role) {
        return attributes.stream().filter(a -> a.role() == role).toList();
    }
}
