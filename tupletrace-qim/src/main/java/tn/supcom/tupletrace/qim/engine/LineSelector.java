package tn.supcom.tupletrace.qim.engine;

/** Sélection d'une ligne : HMAC-SHA-256(K_sel, ser_tuple) mod γ = 0. */
final class LineSelector {
    private final KeyedPrf prf;
    private final int gamma;

    LineSelector(KeyedPrf prf, MarkingParameters parameters) {
        this.prf = prf;
        this.gamma = parameters.gamma();
    }

    boolean isSelected(byte[] tupleIdentity) {
        return prf.mod(tupleIdentity, gamma) == 0;
    }
}
