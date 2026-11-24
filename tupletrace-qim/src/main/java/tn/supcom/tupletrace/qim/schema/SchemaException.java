package tn.supcom.tupletrace.qim.schema;

/**
 * Refus d'un schéma ou d'un attribut. Le code est stable et peut être renvoyé au client ;
 * le message ne contient que des éléments du schéma, jamais une valeur de tuple.
 */
public final class SchemaException extends RuntimeException {
    public enum Code {
        MISSING_FIELD,
        INVALID_ATTRIBUTE_ID,
        INVALID_NAME,
        INVALID_DECIMAL_SPEC,
        INVALID_VERSION,
        NO_ATTRIBUTE,
        TOO_MANY_ATTRIBUTES,
        DUPLICATE_ATTRIBUTE_ID,
        DUPLICATE_NAME,
        MISSING_KEY,
        TOLERANCE_ON_UNMARKABLE,
        STEP_ON_UNMARKABLE,
        ELIGIBLE_NOT_NUMERIC,
        TOLERANCE_NOT_POSITIVE,
        STEP_NOT_POSITIVE,
        STEP_EXCEEDS_TOLERANCE,
        STEP_FINER_THAN_RESOLUTION,
        STEP_OUT_OF_RANGE
    }

    private final Code code;

    public SchemaException(Code code, String detail) {
        super(code + ": " + detail);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
