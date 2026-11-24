package tn.supcom.tupletrace.qim.detect;

/**
 * Test binomial unilatéral sous H0 : chaque bit relu est correct avec la probabilité 1/2
 * (les données ne portent pas la marque de cette clé). La p-valeur est
 * P(X ≥ k) pour X ~ Binomiale(n, 1/2) : la probabilité d'obtenir au moins k bits corrects par hasard.
 *
 * <p>Le calcul se fait en logarithmes (somme des log C(n, i), puis log-sum-exp) pour rester exact
 * à 1e-9 près même quand n vaut plusieurs dizaines de milliers et que la p-valeur est bien plus
 * petite que le plus petit {@code double} (on renvoie alors aussi son log10).
 */
public final class BinomialTest {
    private static final double LOG_2 = Math.log(2);
    private static final double LOG_10 = Math.log(10);

    private BinomialTest() {}

    /** p-valeur P(X ≥ k), éventuellement arrondie à 0 si elle est inférieure à ~1e-308. */
    public static double upperTail(long n, long k) {
        return Math.min(1.0, Math.exp(logUpperTail(n, k)));
    }

    /** log10 de P(X ≥ k), toujours représentable. */
    public static double log10UpperTail(long n, long k) {
        return logUpperTail(n, k) / LOG_10;
    }

    /** Logarithme népérien de P(X ≥ k). */
    static double logUpperTail(long n, long k) {
        if (n < 0 || k < 0 || k > n) {
            throw new IllegalArgumentException("il faut 0 ≤ k ≤ n");
        }
        if (k == 0) {
            return 0;
        }
        // log C(n, k) par récurrence depuis log C(n, 0) = 0.
        double logTerm = 0;
        for (long i = 0; i < k; i++) {
            logTerm += Math.log(n - i) - Math.log(i + 1);
        }
        // Somme des C(n, i) pour i de k à n, en log-sum-exp « en flux ».
        double max = logTerm;
        double scaledSum = 1;
        for (long i = k; i < n; i++) {
            logTerm += Math.log(n - i) - Math.log(i + 1);   // log C(n, i + 1)
            if (logTerm > max) {
                scaledSum = scaledSum * Math.exp(max - logTerm) + 1;
                max = logTerm;
            } else {
                scaledSum += Math.exp(logTerm - max);
            }
        }
        return Math.min(0, max + Math.log(scaledSum) - n * LOG_2);
    }
}
