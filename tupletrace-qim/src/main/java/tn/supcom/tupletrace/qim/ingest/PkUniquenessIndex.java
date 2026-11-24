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
 * HMAC-SHA-256(clé aléatoire propre à cet import, ser_tuple), tronqué. La taille reste fixe
 * quelle que soit la longueur de la clé, aucune valeur de pk n'est conservée, et la clé
 * aléatoire empêche de fabriquer à l'avance des collisions. Une empreinte répétée est toujours
 * un refus (doublon ou collision) : une collision n'est jamais acceptée comme une clé distincte.
 */
final class PkUniquenessIndex {
    private record Fingerprint(long high, long low) {}

    private final Mac hmac;
    private final Set<Fingerprint> seen = new HashSet<>();

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
        var digest = ByteBuffer.wrap(hmac.doFinal(serializedTuple));
        return seen.add(new Fingerprint(digest.getLong(), digest.getLong()));
    }
}
