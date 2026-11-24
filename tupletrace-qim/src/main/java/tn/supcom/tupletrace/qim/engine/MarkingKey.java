package tn.supcom.tupletrace.qim.engine;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Clé maîtresse de marquage Km (256 bits), fournie par l'appelant : dans la plateforme elle
 * viendra du credential store via KeyService, jamais du code ni de la base. Le moteur n'en tire
 * que des sous-clés HKDF liées à un usage, un jeu et une version de schéma.
 *
 * <p>{@link #close()} remplit la copie interne de zéros ; {@link #toString()} ne l'affiche jamais.
 */
public final class MarkingKey implements AutoCloseable {
    public static final int LENGTH = 32;
    private static final byte[] INFO_PREFIX = "TupleTrace-QIM/1".getBytes(StandardCharsets.US_ASCII);

    private final byte[] master;
    private boolean closed;

    private MarkingKey(byte[] master) {
        this.master = master;
    }

    /** Copie la clé : l'appelant reste libre d'effacer son propre tableau. */
    public static MarkingKey of(byte[] master) {
        Objects.requireNonNull(master, "clé");
        if (master.length != LENGTH) {
            throw new IllegalArgumentException("la clé de marquage doit faire " + LENGTH + " octets");
        }
        return new MarkingKey(master.clone());
    }

    /** Clé aléatoire, pour les tests et les démonstrations. */
    public static MarkingKey generate() {
        var bytes = new byte[LENGTH];
        new SecureRandom().nextBytes(bytes);
        try {
            return of(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * Sous-clé = HKDF-SHA-256(Km, info), avec
     * info = "TupleTrace-QIM/1" || 0x00 || usage || 0x00 || id_jeu (16 octets) || version (4 octets).
     * Les libellés d'usage ne contiennent pas d'octet nul, donc l'encodage n'est pas ambigu.
     */
    byte[] subkey(KeyUsage usage, UUID datasetId, int schemaVersion) {
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(datasetId, "id_jeu");
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("clé de marquage déjà effacée");
            }
            var info = new ByteArrayOutputStream(64);
            info.writeBytes(INFO_PREFIX);
            info.write(0);
            info.writeBytes(usage.label().getBytes(StandardCharsets.US_ASCII));
            info.write(0);
            info.writeBytes(ByteBuffer.allocate(20)
                    .putLong(datasetId.getMostSignificantBits())
                    .putLong(datasetId.getLeastSignificantBits())
                    .putInt(schemaVersion)
                    .array());
            return Hkdf.derive(master, null, info.toByteArray(), LENGTH);
        }
    }

    @Override
    public synchronized void close() {
        Arrays.fill(master, (byte) 0);
        closed = true;
    }

    @Override
    public String toString() {
        return "MarkingKey[****]";
    }
}
