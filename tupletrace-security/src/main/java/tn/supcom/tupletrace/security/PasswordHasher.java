package tn.supcom.tupletrace.security;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.bouncycastle.util.Strings;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

final class PasswordHasher {
    private static final SecureRandom RANDOM = new SecureRandom();
    static final int HASH_BYTES = 32;

    static byte[] newSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return salt;
    }

    static boolean accepts(char[] password) {
        if (password == null || password.length > 1024) return false;
        int length = 0;
        for (int i = 0; i < password.length; length++) {
            int point = Character.codePointAt(password, i);
            if (point >= Character.MIN_SURROGATE && point <= Character.MAX_SURROGATE) return false;
            i += Character.charCount(point);
        }
        return length >= 12;
    }

    static byte[] derive(char[] password, byte[] salt) {
        var parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13).withSalt(salt)
                .withMemoryAsKB(65536).withIterations(3).withParallelism(1).build();
        var generator = new Argon2BytesGenerator();
        byte[] utf8 = Strings.toUTF8ByteArray(password);
        try {
            generator.init(parameters);
            byte[] verifier = new byte[HASH_BYTES];
            generator.generateBytes(utf8, verifier);
            return verifier;
        } finally {
            Arrays.fill(utf8, (byte) 0);
            parameters.clear();
        }
    }

    static boolean matches(char[] password, byte[] salt, byte[] expected) {
        byte[] derived = derive(password, salt);
        try {
            return MessageDigest.isEqual(derived, expected);
        } finally {
            Arrays.fill(derived, (byte) 0);
        }
    }

    private PasswordHasher() {}
}
