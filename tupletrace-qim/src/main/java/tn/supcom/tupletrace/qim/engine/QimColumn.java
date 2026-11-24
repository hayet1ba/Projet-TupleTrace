package tn.supcom.tupletrace.qim.engine;

import tn.supcom.tupletrace.qim.canonical.CanonicalValue;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.DecimalValue;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.IntegerValue;
import tn.supcom.tupletrace.qim.engine.MarkingRefusedException.Code;
import tn.supcom.tupletrace.qim.schema.Attribute;
import tn.supcom.tupletrace.qim.schema.AttributeType;
import tn.supcom.tupletrace.qim.schema.Role;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Une colonne ELIGIBLE vue par le quantificateur : pas δ, écart maximal T et plage autorisée,
 * tous exprimés en unités de la colonne (10^-s pour DECIMAL(p, s), 1 pour INTEGER).
 */
public final class QimColumn {
    private final Attribute attribute;
    private final int scale;
    private final long stepUnits;
    private final long maxDeviationUnits;
    private final long maxMagnitude;

    public QimColumn(Attribute attribute) {
        if (attribute.role() != Role.ELIGIBLE) {
            throw new IllegalArgumentException("colonne non ELIGIBLE");
        }
        this.attribute = attribute;
        switch (attribute.type()) {
            case AttributeType.DecimalType decimal -> {
                scale = decimal.scale();
                maxMagnitude = decimal.maxUnscaled();
            }
            case AttributeType.IntegerType _ -> {
                scale = 0;
                maxMagnitude = Long.MAX_VALUE;
            }
            default -> throw new IllegalArgumentException("colonne non numérique");
        }
        // Le schéma garantit δ > 0, δ ≤ T, δ multiple de la résolution et représentable.
        stepUnits = attribute.step().movePointRight(scale).longValueExact();
        var tolerance = attribute.tolerance().movePointRight(scale).setScale(0, RoundingMode.FLOOR);
        maxDeviationUnits = tolerance.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) >= 0
                ? Long.MAX_VALUE : tolerance.longValueExact();
    }

    public Attribute attribute() {
        return attribute;
    }

    /** Valeur en unités de la colonne, ou {@code null} si la cellule est vide (non marquable). */
    public Long units(CanonicalValue value) {
        return switch (value) {
            case CanonicalValue.NullValue _ -> null;
            case IntegerValue integer when scale == 0 && attribute.type() instanceof AttributeType.IntegerType ->
                    integer.value();
            case DecimalValue decimal when decimal.scale() == scale
                    && attribute.type() instanceof AttributeType.DecimalType -> decimal.unscaled();
            default -> throw new MarkingRefusedException(Code.TYPE_MISMATCH, attribute.attributeId());
        };
    }

    /** Valeur canonique correspondant à un nombre d'unités. */
    public CanonicalValue value(long units) {
        return attribute.type() instanceof AttributeType.IntegerType
                ? new IntegerValue(units) : new DecimalValue(units, scale);
    }

    /**
     * Insère le bit dans la plage du type. Refus PRECISION_EXCEEDED si le seul point admissible
     * est à plus de δ (la borne du type l'impose) ; refus TOLERANCE_EXCEEDED si l'écart dépassait T,
     * ce que le schéma (δ ≤ T) rend impossible : ce contrôle est une défense en profondeur.
     */
    public long embed(long units, int bit) {
        long marked;
        long deviation;
        try {
            marked = QimQuantizer.embed(units, stepUnits, bit, -maxMagnitude, maxMagnitude);
            deviation = Math.abs(Math.subtractExact(marked, units));
        } catch (ArithmeticException noAdmissiblePoint) {
            throw new MarkingRefusedException(Code.PRECISION_EXCEEDED, attribute.attributeId());
        }
        if (deviation > stepUnits) {
            throw new MarkingRefusedException(Code.PRECISION_EXCEEDED, attribute.attributeId());
        }
        if (deviation > maxDeviationUnits) {
            throw new MarkingRefusedException(Code.TOLERANCE_EXCEEDED, attribute.attributeId());
        }
        return marked;
    }

    public int extract(long units) {
        return QimQuantizer.extract(units, stepUnits);
    }

    /** Écart entre deux valeurs, en unités de la colonne converties dans l'unité du schéma. */
    BigDecimal toColumnUnit(long units) {
        return BigDecimal.valueOf(units, scale);
    }
}
