package tn.supcom.tupletrace.qim.schema;

/** Type déclaré d'un attribut. L'ensemble des types est fermé. */
public sealed interface AttributeType {

    /** Entier signé 64 bits. */
    record IntegerType() implements AttributeType {}

    /**
     * Décimal exact DECIMAL(p, s). La précision est limitée à 18 chiffres pour que toute valeur
     * non mise à l'échelle tienne dans un {@code long}.
     */
    record DecimalType(int precision, int scale) implements AttributeType {
        public static final int MAX_PRECISION = 18;

        public DecimalType {
            if (precision < 1 || precision > MAX_PRECISION || scale < 0 || scale > precision) {
                throw new SchemaException(SchemaException.Code.INVALID_DECIMAL_SPEC,
                        "DECIMAL(" + precision + "," + scale + ")");
            }
        }

        /** Plus grande valeur absolue non mise à l'échelle : 10^p − 1. */
        public long maxUnscaled() {
            long max = 1;
            for (int i = 0; i < precision; i++) {
                max *= 10;
            }
            return max - 1;
        }
    }

    /** Texte UTF-8 normalisé NFC. Jamais éligible au marquage. */
    record TextType() implements AttributeType {}

    /** Date ISO 8601. Jamais éligible au marquage. */
    record DateType() implements AttributeType {}

    /** Code de catégorie. Jamais éligible au marquage. */
    record CategoryType() implements AttributeType {}

    static AttributeType integer() {
        return new IntegerType();
    }

    static AttributeType decimal(int precision, int scale) {
        return new DecimalType(precision, scale);
    }

    static AttributeType text() {
        return new TextType();
    }

    static AttributeType date() {
        return new DateType();
    }

    static AttributeType category() {
        return new CategoryType();
    }
}
