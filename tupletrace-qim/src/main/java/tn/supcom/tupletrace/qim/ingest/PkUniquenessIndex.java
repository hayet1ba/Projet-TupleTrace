package tn.supcom.tupletrace.qim.ingest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Suit l'unicité des pk sur tout le jeu, et pas seulement à l'intérieur d'un lot (cahier
 * conceptuel §5.1). On ne garde qu'une empreinte de 128 bits par pk :
 * HMAC-SHA-256(clé aléatoire propre à cette lecture, ser_tuple), tronqué. La taille reste fixe
 * quelle que soit la longueur de la clé, aucune valeur de pk n'est conservée, et la clé
 * aléatoire empêche de fabriquer à l'avance des collisions. Une empreinte répétée est toujours
 * traitée comme un doublon : une collision n'est jamais acceptée comme une clé distincte.
 */
final class PkUniquenessIndex {
    /** Rang d'apparition d'une pk. */
    enum Occurrence {
        FIRST,
        /** Deuxième apparition : la pk devient connue comme répétée. */
        FIRST_REPEAT,
        /** Troisième apparition ou plus. */
        LATER_REPEAT
    }

    private record Fingerprint(long high, long low) {}

    private final Mac hmac;
    private final Set<Fingerprint> seen = new HashSet<>();
    private final Set<Fingerprint> repeated = new HashSet<>();

    PkUniquenessIndex() {
        var key = new byte[32];
        new SecureRandom().nextBytes(key);
        try {
            hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(key, "HmacSHA256"));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("HMAC-SHA-256 indisponible", unavailable);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** {@code true} si la pk est nouvelle, {@code false} si elle a déjà été vue. */
    boolean add(byte[] serializedTuple) {
        return record(serializedTuple) == Occurrence.FIRST;
    }

    Occurrence record(byte[] serializedTuple) {
        var digest = ByteBuffer.wrap(hmac.doFinal(serializedTuple));
        var fingerprint = new Fingerprint(digest.getLong(), digest.getLong());
        if (seen.add(fingerprint)) {
            return Occurrence.FIRST;
        }
        return repeated.add(fingerprint) ? Occurrence.FIRST_REPEAT : Occurrence.LATER_REPEAT;
    }
}
