package tn.supcom.tupletrace.qim.canonical;

/**
 * Refus d'une valeur ou d'un tuple canonique. Le message ne contient jamais la valeur refusée,
 * seulement l'attribut concerné.
 */
public final class CanonicalException extends RuntimeException {
    public enum Code {
        MISSING_FIELD,
        MALFORMED_TEXT,
        DECIMAL_SCALE,
        DECIMAL_PRECISION,
        UNKNOWN_ATTRIBUTE,
        MISSING_VALUE,
        NULL_KEY,
        TYPE_MISMATCH
    }

    private final Code code;

    public CanonicalException(Code code, String detail) {
        super(code + ": " + detail);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
