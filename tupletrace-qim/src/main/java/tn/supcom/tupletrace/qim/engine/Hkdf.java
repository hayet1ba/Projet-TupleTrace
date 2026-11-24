package tn.supcom.tupletrace.qim.engine;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Objects;

/** HKDF-SHA-256 (RFC 5869), écrit avec la seule JCA. */
final class Hkdf {
    static final int HASH_LENGTH = 32;
    static final int MAX_OUTPUT = 255 * HASH_LENGTH;

    private Hkdf() {}

    /** Extract puis Expand. {@code salt} vide ou nul : HASH_LENGTH octets à zéro (RFC 5869 §2.2). */
    static byte[] derive(byte[] ikm, byte[] salt, byte[] info, int length) {
        var prk = extract(salt, ikm);
        try {
            return expand(prk, info, length);
        } finally {
            Arrays.fill(prk, (byte) 0);
        }
    }

    static byte[] extract(byte[] salt, byte[] ikm) {
        Objects.requireNonNull(ikm, "ikm");
        var effectiveSalt = salt == null || salt.length == 0 ? new byte[HASH_LENGTH] : salt;
        return hmac(effectiveSalt).doFinal(ikm);
    }

    static byte[] expand(byte[] prk, byte[] info, int length) {
        Objects.requireNonNull(prk, "prk");
        if (length < 1 || length > MAX_OUTPUT) {
            throw new IllegalArgumentException("longueur HKDF hors de [1, " + MAX_OUTPUT + "]");
        }
        var mac = hmac(prk);
        var output = new ByteArrayOutputStream(length + HASH_LENGTH);
        var previous = new byte[0];
        for (int counter = 1; output.size() < length; counter++) {
            mac.update(previous);
            mac.update(info == null ? new byte[0] : info);
            mac.update((byte) counter);
            Arrays.fill(previous, (byte) 0);
            previous = mac.doFinal();
            output.writeBytes(previous);
        }
        Arrays.fill(previous, (byte) 0);
        var all = output.toByteArray();
        try {
            return Arrays.copyOf(all, length);
        } finally {
            Arrays.fill(all, (byte) 0);
        }
    }

    static Mac hmac(byte[] key) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac;
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("HMAC-SHA-256 indisponible", unavailable);
        }
    }
}
