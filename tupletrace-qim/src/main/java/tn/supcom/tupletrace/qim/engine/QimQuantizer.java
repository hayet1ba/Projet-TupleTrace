package tn.supcom.tupletrace.qim.engine;

/**
 * Quantification à pas δ (QIM), en entiers exacts : les valeurs sont exprimées en unités de la
 * colonne (10^-s pour DECIMAL(p, s), 1 pour INTEGER) et δ aussi.
 *
 * <p>Insérer le bit b : remplacer u par le multiple m·δ le plus proche dont m a la parité de b
 * (pair pour 0, impair pour 1). Deux points de même parité sont espacés de 2δ, donc l'écart est
 * toujours ≤ δ. En cas d'égalité, on prend le point supérieur (exemple du cahier des charges
 * §2.2 : 21,37 avec δ = 0,01 et le bit 0 donne 21,38), sauf s'il sort de la plage du type :
 * on prend alors l'autre, qui est à la même distance.
 *
 * <p>Lire le bit : parité de round(u / δ). Un bruit d'amplitude strictement inférieure à δ/2
 * ne change pas l'arrondi, donc ne change pas le bit.
 */
public final class QimQuantizer {
    private QimQuantizer() {}

    /** Insertion sans autre borne que celle des entiers 64 bits. */
    public static long embed(long value, long step, int bit) {
        return embed(value, step, bit, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    /**
     * Insertion limitée à [lower, upper]. Le résultat peut alors s'écarter de plus de δ si le seul
     * point admissible est plus loin : c'est à l'appelant de le refuser.
     *
     * @throws ArithmeticException si aucun point de la bonne parité n'est dans la plage
     */
    public static long embed(long value, long step, int bit, long lower, long upper) {
        requireStep(step);
        if (bit != 0 && bit != 1) {
            throw new IllegalArgumentException("un bit vaut 0 ou 1");
        }
        long quotient = Math.floorDiv(value, step);
        long best = 0;
        long bestDistance = -1;
        for (int offset = -1; offset <= 2; offset++) {
            long m;
            long candidate;
            long distance;
            try {
                m = Math.addExact(quotient, offset);
                if (Math.floorMod(m, 2) != bit) {
                    continue;
                }
                candidate = Math.multiplyExact(m, step);
                distance = Math.abs(Math.subtractExact(value, candidate));
            } catch (ArithmeticException outOfLong) {
                continue;
            }
            if (candidate < lower || candidate > upper) {
                continue;
            }
            // « <= » : à distance égale, le candidat plus grand (m croissant) l'emporte.
            if (bestDistance < 0 || distance <= bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        if (bestDistance < 0) {
            throw new ArithmeticException("aucun point de la bonne parité dans la plage");
        }
        return best;
    }

    public static int extract(long value, long step) {
        requireStep(step);
        long quotient = Math.floorDiv(value, step);
        long remainder = Math.floorMod(value, step);       // 0 <= remainder < step, sans débordement
        long nearest = remainder >= step - remainder ? quotient + 1 : quotient;
        return (int) Math.floorMod(nearest, 2);
    }

    private static void requireStep(long step) {
        if (step <= 0) {
            throw new IllegalArgumentException("le pas δ doit être strictement positif");
        }
    }
}
