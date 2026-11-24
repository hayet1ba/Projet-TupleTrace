package tn.supcom.tupletrace.qim.canonical;

import tn.supcom.tupletrace.qim.canonical.CanonicalException.Code;
import tn.supcom.tupletrace.qim.schema.AttributeType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;

/**
 * Valeur typée d'une cellule, sous sa forme canonique (cahier conceptuel §2.2). Deux écritures
 * différentes d'une même valeur (« 21.37 » et « 21.370 », « é » composé ou décomposé) donnent
 * la même valeur canonique.
 */
public sealed interface CanonicalValue {

    /** Valeur absente : distincte de la chaîne vide, interdite dans une clé. */
    record NullValue() implements CanonicalValue {}

    /** Entier signé 64 bits. */
    record IntegerValue(long value) implements CanonicalValue {}

    /** Décimal exact : entier non mis à l'échelle et échelle s du schéma. */
    record DecimalValue(long unscaled, int scale) implements CanonicalValue {
        public DecimalValue {
            if (scale < 0 || scale > AttributeType.DecimalType.MAX_PRECISION) {
                throw new CanonicalException(Code.DECIMAL_SCALE, "échelle hors plage");
            }
        }

        /** Ramène un nombre à l'échelle du type, sans jamais arrondir. */
        public static DecimalValue of(BigDecimal number, AttributeType.DecimalType type) {
            if (number == null || type == null) {
                throw new CanonicalException(Code.MISSING_FIELD, "décimal");
            }
            BigDecimal scaled;
            try {
                scaled = number.setScale(type.scale(), RoundingMode.UNNECESSARY);
            } catch (ArithmeticException moreDigitsThanScale) {
                throw new CanonicalException(Code.DECIMAL_SCALE, "trop de décimales");
            }
            var unscaled = scaled.unscaledValue();
            if (unscaled.abs().compareTo(BigDecimal.valueOf(type.maxUnscaled()).toBigInteger()) > 0) {
                throw new CanonicalException(Code.DECIMAL_PRECISION, "trop de chiffres");
            }
            return new DecimalValue(unscaled.longValueExact(), type.scale());
        }

        public BigDecimal toBigDecimal() {
            return BigDecimal.valueOf(unscaled, scale);
        }
    }

    /** Texte UTF-8 normalisé NFC. */
    record TextValue(String value) implements CanonicalValue {
        public TextValue {
            value = wellFormed(value);
        }
    }

    /** Date : nombre de jours depuis 1970-01-01. */
    record DateValue(long epochDay) implements CanonicalValue {
        public static DateValue of(LocalDate date) {
            if (date == null) {
                throw new CanonicalException(Code.MISSING_FIELD, "date");
            }
            return new DateValue(date.toEpochDay());
        }
    }

    /** Code de catégorie, UTF-8 normalisé NFC. */
    record CategoryValue(String code) implements CanonicalValue {
        public CategoryValue {
            code = wellFormed(code);
        }
    }

    static NullValue nullValue() {
        return new NullValue();
    }

    /**
     * Indique si une valeur est compatible avec le type déclaré d'un attribut.
     * {@link NullValue} est compatible avec tout type ; l'interdiction d'un nul dans une clé
     * est vérifiée à part.
     */
    static boolean fits(AttributeType type, CanonicalValue value) {
        return switch (value) {
            case NullValue _ -> true;
            case IntegerValue _ -> type instanceof AttributeType.IntegerType;
            case DecimalValue decimal -> type instanceof AttributeType.DecimalType declared
                    && decimal.scale() == declared.scale()
                    && decimal.unscaled() >= -declared.maxUnscaled()
                    && decimal.unscaled() <= declared.maxUnscaled();
            case TextValue _ -> type instanceof AttributeType.TextType;
            case DateValue _ -> type instanceof AttributeType.DateType;
            case CategoryValue _ -> type instanceof AttributeType.CategoryType;
        };
    }

    /**
     * Normalise en NFC et refuse un texte qui ne s'encode pas en UTF-8 (surrogate isolé) :
     * sinon deux textes différents pourraient produire les mêmes octets.
     */
    private static String wellFormed(String text) {
        if (text == null) {
            throw new CanonicalException(Code.MISSING_FIELD, "texte");
        }
        var normalized = Normalizer.normalize(text, Normalizer.Form.NFC);
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(normalized)) {
            throw new CanonicalException(Code.MALFORMED_TEXT, "texte non encodable en UTF-8");
        }
        return normalized;
    }
}
