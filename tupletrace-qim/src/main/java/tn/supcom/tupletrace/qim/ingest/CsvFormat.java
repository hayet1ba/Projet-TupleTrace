package tn.supcom.tupletrace.qim.ingest;

/**
 * Format déclaré à l'import : séparateur de champs et séparateur décimal. Rien n'est deviné.
 * Les deux séparateurs doivent être différents, sinon « 21,37 » serait ambigu.
 */
public record CsvFormat(char fieldSeparator, char decimalSeparator) {
    public static final CsvFormat COMMA_DOT = new CsvFormat(',', '.');
    public static final CsvFormat SEMICOLON_COMMA = new CsvFormat(';', ',');

    public CsvFormat {
        if (",;\t|".indexOf(fieldSeparator) < 0) {
            throw new IllegalArgumentException("séparateur de champs non autorisé");
        }
        if (decimalSeparator != '.' && decimalSeparator != ',') {
            throw new IllegalArgumentException("séparateur décimal non autorisé");
        }
        if (fieldSeparator == decimalSeparator) {
            throw new IllegalArgumentException("séparateurs de champs et décimal identiques");
        }
    }
}
