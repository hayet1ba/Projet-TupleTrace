package tn.supcom.tupletrace.qim.ingest;

/**
 * Refus d'un fichier CSV. Le code est stable et peut être renvoyé au client. Le numéro de ligne
 * sert au journal serveur ; ni le message ni les champs ne contiennent la valeur refusée.
 */
public final class CsvRejectedException extends RuntimeException {
    public enum Code {
        FILE_TOO_LARGE,
        TOO_MANY_LINES,
        LINE_TOO_LONG,
        TOO_MANY_COLUMNS,
        INVALID_UTF8,
        EMPTY_FILE,
        BLANK_LINE,
        MALFORMED_QUOTES,
        UNTERMINATED_QUOTE,
        EMPTY_HEADER,
        DUPLICATE_HEADER,
        UNKNOWN_COLUMN,
        MISSING_COLUMN,
        FIELD_COUNT_MISMATCH,
        NULL_KEY,
        DUPLICATE_KEY,
        AMBIGUOUS_VALUE,
        INVALID_VALUE,
        OUT_OF_RANGE
    }

    private final Code code;
    private final long lineNumber;

    public CsvRejectedException(Code code, long lineNumber) {
        super(code + " (ligne " + lineNumber + ")");
        this.code = code;
        this.lineNumber = lineNumber;
    }

    public Code code() {
        return code;
    }

    /** Ligne physique concernée (1 = en-tête), réservée au journal serveur. */
    public long lineNumber() {
        return lineNumber;
    }
}
