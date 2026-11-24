package tn.supcom.tupletrace.qim.schema;

import tn.supcom.tupletrace.qim.schema.AttributeType.DecimalType;
import tn.supcom.tupletrace.qim.schema.AttributeType.IntegerType;
import tn.supcom.tupletrace.qim.schema.SchemaException.Code;

import java.math.BigDecimal;
import java.text.Normalizer;

/**
 * Attribut d'un schéma versionné. Les règles de marquage sont vérifiées à la construction :
 * un attribut invalide ne peut pas exister.
 *
 * @param attributeId identifiant sémantique stable (≥ 1), indépendant de la position de la colonne
 * @param name        nom affiché, normalisé NFC
 * @param tolerance   écart maximal admis T ; vaut 0 pour CLE et INTERDIT
 * @param step        pas de quantification δ ; {@code null} pour CLE et INTERDIT
 */
public record Attribute(int attributeId, String name, AttributeType type, Role role,
                        BigDecimal tolerance, BigDecimal step) {

    public Attribute {
        if (attributeId < 1) {
            throw new SchemaException(Code.INVALID_ATTRIBUTE_ID, "attribut " + attributeId);
        }
        name = normalizedName(attributeId, name);
        if (type == null || role == null || tolerance == null) {
            throw new SchemaException(Code.MISSING_FIELD, "attribut " + attributeId);
        }
        if (role == Role.ELIGIBLE) {
            checkMarkable(attributeId, type, tolerance, step);
        } else {
            if (tolerance.signum() != 0) {
                throw new SchemaException(Code.TOLERANCE_ON_UNMARKABLE, "attribut " + attributeId);
            }
            if (step != null) {
                throw new SchemaException(Code.STEP_ON_UNMARKABLE, "attribut " + attributeId);
            }
        }
    }

    public static Attribute key(int attributeId, String name, AttributeType type) {
        return new Attribute(attributeId, name, type, Role.CLE, BigDecimal.ZERO, null);
    }

    public static Attribute forbidden(int attributeId, String name, AttributeType type) {
        return new Attribute(attributeId, name, type, Role.INTERDIT, BigDecimal.ZERO, null);
    }

    public static Attribute eligible(int attributeId, String name, AttributeType type,
                                     BigDecimal tolerance, BigDecimal step) {
        return new Attribute(attributeId, name, type, Role.ELIGIBLE, tolerance, step);
    }

    private static String normalizedName(int attributeId, String name) {
        if (name == null) {
            throw new SchemaException(Code.MISSING_FIELD, "attribut " + attributeId);
        }
        var normalized = Normalizer.normalize(name, Normalizer.Form.NFC);
        if (normalized.isBlank() || !normalized.equals(normalized.strip())
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new SchemaException(Code.INVALID_NAME, "attribut " + attributeId);
        }
        return normalized;
    }

    private static void checkMarkable(int attributeId, AttributeType type,
                                      BigDecimal tolerance, BigDecimal step) {
        int scale = switch (type) {
            case IntegerType _ -> 0;
            case DecimalType decimal -> decimal.scale();
            default -> throw new SchemaException(Code.ELIGIBLE_NOT_NUMERIC, "attribut " + attributeId);
        };
        if (tolerance.signum() <= 0) {
            throw new SchemaException(Code.TOLERANCE_NOT_POSITIVE, "attribut " + attributeId);
        }
        if (step == null || step.signum() <= 0) {
            throw new SchemaException(Code.STEP_NOT_POSITIVE, "attribut " + attributeId);
        }
        if (step.compareTo(tolerance) > 0) {
            throw new SchemaException(Code.STEP_EXCEEDS_TOLERANCE, "attribut " + attributeId);
        }
        if (step.stripTrailingZeros().scale() > scale) {
            throw new SchemaException(Code.STEP_FINER_THAN_RESOLUTION, "attribut " + attributeId);
        }
        long units;
        try {
            units = step.movePointRight(scale).longValueExact();
        } catch (ArithmeticException tooLarge) {
            throw new SchemaException(Code.STEP_OUT_OF_RANGE, "attribut " + attributeId);
        }
        if (type instanceof DecimalType decimal && units > decimal.maxUnscaled()) {
            throw new SchemaException(Code.STEP_OUT_OF_RANGE, "attribut " + attributeId);
        }
    }
}
