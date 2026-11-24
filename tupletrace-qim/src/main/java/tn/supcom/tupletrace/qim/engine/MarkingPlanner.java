package tn.supcom.tupletrace.qim.engine;

import tn.supcom.tupletrace.qim.canonical.CanonicalSerializer;
import tn.supcom.tupletrace.qim.canonical.CanonicalTuple;
import tn.supcom.tupletrace.qim.schema.Attribute;
import tn.supcom.tupletrace.qim.schema.DatasetSchema;

import java.util.Objects;
import java.util.Optional;

/**
 * Décide, pour un tuple, s'il porte un bit, dans quelle couche et dans quelle colonne.
 * La décision ne dépend que de la clé, du jeu, de la version du schéma et de la pk : ni de la
 * position de la ligne, ni de celle des colonnes, ni des valeurs non-clés (que le marquage
 * modifie). On retrouve donc les mêmes lignes à l'insertion et à l'extraction.
 */
public final class MarkingPlanner {
    /** Emplacement d'un bit : la couche de la ligne et la colonne choisie. */
    public record Placement(Layer layer, Attribute column) {}

    private final DatasetSchema schema;
    private final LineSelector selector;
    private final LayerAssigner layers;
    private final ColumnChooser columns;

    public MarkingPlanner(MarkingKey key, DatasetSchema schema, MarkingParameters parameters) {
        Objects.requireNonNull(key, "clé");
        this.schema = Objects.requireNonNull(schema, "schéma");
        Objects.requireNonNull(parameters, "paramètres");
        var dataset = schema.datasetId();
        int version = schema.version();
        this.selector = new LineSelector(new KeyedPrf(key.subkey(KeyUsage.SELECTION, dataset, version)), parameters);
        this.layers = new LayerAssigner(new KeyedPrf(key.subkey(KeyUsage.LAYER, dataset, version)));
        this.columns = new ColumnChooser(new KeyedPrf(key.subkey(KeyUsage.COLUMN, dataset, version)), schema);
    }

    /** Vide si la ligne n'est pas sélectionnée. */
    public Optional<Placement> placementOf(CanonicalTuple tuple) {
        var identity = CanonicalSerializer.serializeTuple(schema, tuple);
        if (!selector.isSelected(identity)) {
            return Optional.empty();
        }
        return Optional.of(new Placement(layers.layerOf(identity), columns.choose(identity)));
    }
}
