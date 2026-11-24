package tn.supcom.tupletrace.qim.engine;

/**
 * Paramètres de marquage (cahier des charges §6.2).
 *
 * @param gamma une ligne est sélectionnée si HMAC(K_sel, pk) mod γ = 0 ; γ = 5 donne environ 20 %
 */
public record MarkingParameters(int gamma) {
    public static final int MIN_GAMMA = 2;
    public static final int MAX_GAMMA = 20;
    public static final MarkingParameters DEFAULT = new MarkingParameters(5);

    public MarkingParameters {
        if (gamma < MIN_GAMMA || gamma > MAX_GAMMA) {
            throw new IllegalArgumentException("γ doit être compris entre " + MIN_GAMMA + " et " + MAX_GAMMA);
        }
    }
}
