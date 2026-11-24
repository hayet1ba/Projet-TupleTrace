package tn.supcom.tupletrace.qim.engine;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rapport de qualité du marquage (BF-WM-02, cahier des charges §6.7) : combien de cellules ont été
 * modifiées et de combien, et l'effet sur les extrêmes, la moyenne et la variance de chaque
 * colonne ELIGIBLE. Il ne contient que des statistiques agrégées, jamais une valeur de tuple.
 */
public record QualityReport(long tuples, long selectedRows, long watermarkRows, long markedCells,
                            long skippedNullCells, List<ColumnQuality> columns) {

    public QualityReport {
        columns = List.copyOf(columns);
    }

    /**
     * @param values            cellules non vides de la colonne
     * @param markedCells       cellules qui portent un bit du filigrane
     * @param changedCells      cellules dont la valeur a réellement changé
     * @param maxAbsoluteError  écart maximal (≤ δ ≤ T)
     * @param meanAbsoluteError écart moyen sur les cellules marquées
     */
    public record ColumnQuality(int attributeId, String name, long values, long markedCells, long changedCells,
                                BigDecimal maxAbsoluteError, BigDecimal meanAbsoluteError,
                                BigDecimal minBefore, BigDecimal maxBefore, BigDecimal minAfter, BigDecimal maxAfter,
                                double meanBefore, double meanAfter, double varianceBefore, double varianceAfter) {}

    /** Accumulateur utilisé par le moteur pendant le traitement en flux. */
    static final class Builder {
        private final Map<Integer, ColumnAccumulator> columns = new LinkedHashMap<>();
        private long tuples;
        private long selectedRows;
        private long watermarkRows;
        private long markedCells;
        private long skippedNullCells;

        Builder(List<QimColumn> eligible) {
            eligible.forEach(column -> columns.put(column.attribute().attributeId(), new ColumnAccumulator(column)));
        }

        void tuple() {
            tuples++;
        }

        void selected(Layer layer) {
            selectedRows++;
            if (layer == Layer.WATERMARK) {
                watermarkRows++;
            }
        }

        void skippedNull() {
            skippedNullCells++;
        }

        void marked(QimColumn column, long before, long after) {
            markedCells++;
            columns.get(column.attribute().attributeId()).marked(before, after);
        }

        void observe(QimColumn column, long before, long after) {
            columns.get(column.attribute().attributeId()).observe(before, after);
        }

        QualityReport build() {
            var result = new ArrayList<ColumnQuality>();
            columns.values().forEach(accumulator -> result.add(accumulator.snapshot()));
            return new QualityReport(tuples, selectedRows, watermarkRows, markedCells, skippedNullCells, result);
        }
    }

    private static final class ColumnAccumulator {
        private final QimColumn column;
        private final Statistics before = new Statistics();
        private final Statistics after = new Statistics();
        private long marked;
        private long changed;
        private long maxDeviation;
        private BigDecimal sumDeviation = BigDecimal.ZERO;

        ColumnAccumulator(QimColumn column) {
            this.column = column;
        }

        void marked(long beforeUnits, long afterUnits) {
            long deviation = Math.abs(afterUnits - beforeUnits);
            marked++;
            if (deviation != 0) {
                changed++;
            }
            maxDeviation = Math.max(maxDeviation, deviation);
            sumDeviation = sumDeviation.add(BigDecimal.valueOf(deviation));
        }

        void observe(long beforeUnits, long afterUnits) {
            before.add(beforeUnits);
            after.add(afterUnits);
        }

        ColumnQuality snapshot() {
            var attribute = column.attribute();
            var mean = marked == 0 ? BigDecimal.ZERO
                    : sumDeviation.divide(BigDecimal.valueOf(marked), MathContext.DECIMAL64);
            double unit = column.toColumnUnit(1).doubleValue();
            return new ColumnQuality(attribute.attributeId(), attribute.name(), before.count, marked, changed,
                    column.toColumnUnit(maxDeviation), mean.multiply(column.toColumnUnit(1)),
                    before.count == 0 ? null : column.toColumnUnit(before.min),
                    before.count == 0 ? null : column.toColumnUnit(before.max),
                    after.count == 0 ? null : column.toColumnUnit(after.min),
                    after.count == 0 ? null : column.toColumnUnit(after.max),
                    before.mean * unit, after.mean * unit,
                    before.variance() * unit * unit, after.variance() * unit * unit);
        }
    }

    /** Moyenne et variance par l'algorithme de Welford, stable en un seul passage. */
    private static final class Statistics {
        private long count;
        private double mean;
        private double squares;
        private long min = Long.MAX_VALUE;
        private long max = Long.MIN_VALUE;

        void add(long value) {
            count++;
            double delta = value - mean;
            mean += delta / count;
            squares += delta * (value - mean);
            min = Math.min(min, value);
            max = Math.max(max, value);
        }

        double variance() {
            return count == 0 ? 0 : squares / count;
        }
    }
}
