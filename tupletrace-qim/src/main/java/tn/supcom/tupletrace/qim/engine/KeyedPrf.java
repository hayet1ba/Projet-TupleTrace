package tn.supcom.tupletrace.qim.engine;

import javax.crypto.Mac;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Fonction pseudo-aléatoire à clé : HMAC-SHA-256(sous-clé, ser_tuple), dont on lit les 64
 * premiers bits. Le biais d'un modulo m ≤ 20 sur 2^64 valeurs est négligeable (< 2^-59).
 * Non thread-safe : une instance par traitement.
 */
final class KeyedPrf {
    private final Mac mac;

    KeyedPrf(byte[] subkey) {
        try {
            mac = Hkdf.hmac(subkey);
        } finally {
            Arrays.fill(subkey, (byte) 0);
        }
    }

    long first64(byte[] message) {
        return ByteBuffer.wrap(mac.doFinal(message)).getLong();
    }

    int mod(byte[] message, int modulus) {
        return (int) Long.remainderUnsigned(first64(message), modulus);
    }
}
