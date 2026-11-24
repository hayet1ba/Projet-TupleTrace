package tn.supcom.tupletrace.qim.engine;

/**
 * Refus motivé du marquage (BF-WM-03, SR-INT-02) : le traitement entier échoue plutôt que de
 * produire une copie qui violerait une tolérance ou le type d'une colonne. Le message ne contient
 * que l'attribut concerné, jamais une valeur.
 */
public final class MarkingRefusedException extends RuntimeException {
    public enum Code {
        NO_ELIGIBLE_COLUMN,
        SCHEMA_MISMATCH,
        TYPE_MISMATCH,
        PRECISION_EXCEEDED,
        TOLERANCE_EXCEEDED
    }

    private final Code code;
    private final int attributeId;

    public MarkingRefusedException(Code code, int attributeId) {
        super(code + (attributeId > 0 ? " (attribut " + attributeId + ")" : ""));
        this.code = code;
        this.attributeId = attributeId;
    }

    public Code code() {
        return code;
    }

    /** Attribut concerné, ou 0 si le refus porte sur tout le jeu. */
    public int attributeId() {
        return attributeId;
    }
}
