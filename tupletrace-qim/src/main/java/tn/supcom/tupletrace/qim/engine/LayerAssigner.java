package tn.supcom.tupletrace.qim.engine;

/** Couche d'une ligne sélectionnée : HMAC-SHA-256(K_layer, ser_tuple) mod 3. */
final class LayerAssigner {
    private static final Layer[] LAYERS = Layer.values();

    private final KeyedPrf prf;

    LayerAssigner(KeyedPrf prf) {
        this.prf = prf;
    }

    Layer layerOf(byte[] tupleIdentity) {
        return LAYERS[prf.mod(tupleIdentity, LAYERS.length)];
    }
}
