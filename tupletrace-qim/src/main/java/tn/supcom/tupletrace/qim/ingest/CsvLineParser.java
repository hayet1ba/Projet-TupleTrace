package tn.supcom.tupletrace.qim.ingest;

import tn.supcom.tupletrace.qim.ingest.CsvRejectedException.Code;

import java.util.ArrayList;
import java.util.List;

/**
 * Découpe une ligne selon le séparateur déclaré (sous-ensemble strict de la RFC 4180).
 * <ul>
 *   <li>champ vide non guillemeté → {@code null} (valeur absente) ;</li>
 *   <li>{@code ""} → chaîne vide, distincte de l'absence ;</li>
 *   <li>{@code ""} à l'intérieur d'un champ guillemeté → un guillemet ;</li>
 *   <li>guillemet au milieu d'un champ non guillemeté, ou texte après le guillemet fermant → refus ;</li>
 *   <li>guillemet non refermé sur la ligne → refus : les champs sur plusieurs lignes sont interdits.</li>
 * </ul>
 */
final class CsvLineParser {
    private static final char QUOTE = '"';

    private CsvLineParser() {}

    static List<String> parse(String line, char separator, int maxColumns, long lineNumber) {
        var fields = new ArrayList<String>();
        int i = 0;
        int n = line.length();
        while (true) {
            if (fields.size() == maxColumns) {
                throw new CsvRejectedException(Code.TOO_MANY_COLUMNS, lineNumber);
            }
            if (i < n && line.charAt(i) == QUOTE) {
                var field = new StringBuilder();
                i++;
                while (true) {
                    if (i >= n) {
                        throw new CsvRejectedException(Code.UNTERMINATED_QUOTE, lineNumber);
                    }
                    char c = line.charAt(i++);
                    if (c != QUOTE) {
                        field.append(c);
                    } else if (i < n && line.charAt(i) == QUOTE) {
                        field.append(QUOTE);
                        i++;
                    } else {
                        break;
                    }
                }
                fields.add(field.toString());
                if (i == n) {
                    return fields;
                }
                if (line.charAt(i) != separator) {
                    throw new CsvRejectedException(Code.MALFORMED_QUOTES, lineNumber);
                }
                i++;
            } else {
                int start = i;
                while (i < n && line.charAt(i) != separator) {
                    if (line.charAt(i) == QUOTE) {
                        throw new CsvRejectedException(Code.MALFORMED_QUOTES, lineNumber);
                    }
                    i++;
                }
                fields.add(start == i ? null : line.substring(start, i));
                if (i == n) {
                    return fields;
                }
                i++;
            }
        }
    }
}
