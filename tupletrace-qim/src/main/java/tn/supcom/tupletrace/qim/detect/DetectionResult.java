package tn.supcom.tupletrace.qim.detect;

import tn.supcom.tupletrace.qim.ingest.SuspectSummary;

/**
 * Résultat de la recherche du filigrane. Toutes les statistiques sont calculées par le moteur à
 * partir de l'extrait seul (SR-INT-10) ; elles ne contiennent aucune valeur de tuple.
 *
 * @param bitsRead       n : cellules du filigrane retrouvées et lisibles, hors lignes écartées
 * @param bitsMatching   k : cellules dont le bit relu est celui attendu
 * @param pValue         P(X ≥ k) sous H0 (bits au hasard), arrondie à 0 sous ~1e-308
 * @param log10PValue    log10 de la p-valeur, toujours exact
 * @param threshold      seuil de décision (1e-6)
 * @param minimumBits    n minimal pour conclure (20)
 * @param reason         raison d'un verdict INDETERMINE, {@link Reason#NONE} sinon
 * @param extract        bilan de lecture de l'extrait, dont les lignes écartées pour pk répétée
 */
public record DetectionResult(Verdict verdict, long bitsRead, long bitsMatching, double pValue, double log10PValue,
                              double threshold, long minimumBits, Reason reason, SuspectSummary extract) {

    public static final double THRESHOLD = 1e-6;

    /**
     * 20 bits : c'est le plus petit n pour lequel p < 1e-6 est possible (2^-20 ≈ 9,5e-7). En
     * dessous, même une concordance parfaite ne suffirait pas : un « négatif » ne voudrait rien dire.
     */
    public static final long MINIMUM_BITS = 20;

    public enum Reason {
        NONE,
        /** Une colonne de la clé stable manque dans l'extrait : aucune ligne ne peut être retrouvée. */
        MISSING_KEY_COLUMN,
        /** Un nom de colonne apparaît plusieurs fois : on ne sait pas laquelle lire. */
        AMBIGUOUS_HEADER,
        /** Trop peu de bits relus (après exclusion des lignes ambiguës) pour pouvoir conclure. */
        INSUFFICIENT_BITS
    }

    /** Lignes écartées parce que leur pk apparaît plusieurs fois dans l'extrait. */
    public long excludedDuplicateRows() {
        return extract.rowsWithRepeatedKey();
    }

    /** Applique la règle de décision ; seul le détecteur fournit n, k et le bilan. */
    static DetectionResult decide(long n, long k, SuspectSummary extract) {
        double log10 = BinomialTest.log10UpperTail(n, k);
        double p = BinomialTest.upperTail(n, k);
        Reason reason;
        if (!extract.keyColumnsPresent()) {
            reason = Reason.MISSING_KEY_COLUMN;
        } else if (extract.ambiguousHeader()) {
            reason = Reason.AMBIGUOUS_HEADER;
        } else if (n < MINIMUM_BITS) {
            reason = Reason.INSUFFICIENT_BITS;
        } else {
            reason = Reason.NONE;
        }
        Verdict verdict = reason != Reason.NONE ? Verdict.INDETERMINE
                : p < THRESHOLD ? Verdict.POSITIF : Verdict.NEGATIF;
        return new DetectionResult(verdict, n, k, p, log10, THRESHOLD, MINIMUM_BITS, reason, extract);
    }
}
