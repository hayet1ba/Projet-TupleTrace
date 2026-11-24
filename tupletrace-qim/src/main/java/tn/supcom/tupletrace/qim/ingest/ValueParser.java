package tn.supcom.tupletrace.qim.ingest;

import tn.supcom.tupletrace.qim.canonical.CanonicalException;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.CategoryValue;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.DateValue;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.DecimalValue;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.IntegerValue;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.TextValue;
import tn.supcom.tupletrace.qim.ingest.CsvRejectedException.Code;
import tn.supcom.tupletrace.qim.schema.AttributeType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Transforme le texte d'une cellule en valeur canonique selon le type déclaré. Rien n'est deviné
 * ni arrondi : une écriture qui admet deux lectures est refusée (SR-INT-13).
 */
final class ValueParser {
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]+");
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

    private final char decimalSeparator;
    private final char otherSeparator;
    private final Pattern decimal;

    ValueParser(CsvFormat format) {
        decimalSeparator = format.decimalSeparator();
        otherSeparator = decimalSeparator == '.' ? ',' : '.';
        decimal = Pattern.compile("-?[0-9]+(" + Pattern.quote(String.valueOf(decimalSeparator)) + "[0-9]+)?");
    }

    /** {@code raw == null} (champ vide non guillemeté) donne la valeur nulle. */
    CanonicalValue parse(String raw, AttributeType type, long lineNumber) {
        if (raw == null) {
            return CanonicalValue.nullValue();
        }
        try {
            return switch (type) {
                case AttributeType.IntegerType _ -> parseInteger(raw, lineNumber);
                case AttributeType.DecimalType declared -> parseDecimal(raw, declared, lineNumber);
                case AttributeType.DateType _ -> parseDate(raw, lineNumber);
                case AttributeType.TextType _ -> new TextValue(raw);
                case AttributeType.CategoryType _ -> new CategoryValue(raw);
            };
        } catch (CanonicalException refused) {
            throw new CsvRejectedException(switch (refused.code()) {
                case DECIMAL_SCALE, DECIMAL_PRECISION -> Code.OUT_OF_RANGE;
                default -> Code.INVALID_VALUE;
            }, lineNumber);
        }
    }

    /**
     * Lecture tolérante d'un nombre dans un extrait suspect : le nombre de décimales n'est pas
     * borné (un bruit a pu en ajouter), mais l'écriture doit rester non ambiguë.
     *
     * @return le nombre, ou {@code null} si la cellule est vide, ambiguë ou mal formée
     */
    BigDecimal parseLenientNumber(String raw) {
        if (raw == null || raw.indexOf(otherSeparator) >= 0 || !decimal.matcher(raw).matches()) {
            return null;
        }
        return new BigDecimal(raw.replace(decimalSeparator, '.'));
    }

    private static IntegerValue parseInteger(String raw, long lineNumber) {
        if (raw.indexOf('.') >= 0 || raw.indexOf(',') >= 0) {
            // « 1,234 » ou « 1.234 » : séparateur de milliers ou décimal ? On ne choisit pas.
            throw new CsvRejectedException(Code.AMBIGUOUS_VALUE, lineNumber);
        }
        if (!INTEGER.matcher(raw).matches()) {
            throw new CsvRejectedException(Code.INVALID_VALUE, lineNumber);
        }
        try {
            return new IntegerValue(Long.parseLong(raw));
        } catch (NumberFormatException overflow) {
            throw new CsvRejectedException(Code.OUT_OF_RANGE, lineNumber);
        }
    }

    private DecimalValue parseDecimal(String raw, AttributeType.DecimalType type, long lineNumber) {
        if (raw.indexOf(otherSeparator) >= 0
                || raw.indexOf(decimalSeparator) != raw.lastIndexOf(decimalSeparator)) {
            throw new CsvRejectedException(Code.AMBIGUOUS_VALUE, lineNumber);
        }
        if (!decimal.matcher(raw).matches()) {
            throw new CsvRejectedException(Code.INVALID_VALUE, lineNumber);
        }
        return DecimalValue.of(new BigDecimal(raw.replace(decimalSeparator, '.')), type);
    }

    private static DateValue parseDate(String raw, long lineNumber) {
        if (!DATE.matcher(raw).matches()) {
            throw new CsvRejectedException(Code.INVALID_VALUE, lineNumber);
        }
        try {
            return DateValue.of(LocalDate.parse(raw));
        } catch (DateTimeParseException impossibleDate) {
            throw new CsvRejectedException(Code.INVALID_VALUE, lineNumber);
        }
    }
}
