package tn.supcom.tupletrace.security;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

import static java.nio.charset.StandardCharsets.US_ASCII;

public final class TotpFactor {
    public record Proof(UUID factor, Instant time) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private TotpFactor() {}

    private static Cipher cipher(int mode, UUID user, UUID factor, byte[] nonce)
            throws GeneralSecurityException, IOException {
        var directory = System.getenv("TT_KEY_DIR");
        var credential = System.getenv("TT_KEYSTORE_PASSWORD");
        if (directory == null || !Path.of(directory).isAbsolute() || credential == null || credential.isEmpty()) {
            throw new KeyStoreException("Missing TOTP key configuration");
        }
        char[] password = credential.toCharArray();
        try {
            var store = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(Path.of(directory, "totp.p12"))) {
                store.load(input, password);
            }
            if (!(store.getKey("tt-totp", password) instanceof SecretKey key) || !"AES".equals(key.getAlgorithm())) {
                throw new KeyStoreException("Expected TOTP wrapping key");
            }
            byte[] encoded = key.getEncoded();
            try {
                if (encoded == null || encoded.length != 32) throw new KeyStoreException("Expected AES-256 key");
            } finally {
                if (encoded != null) Arrays.fill(encoded, (byte) 0);
            }
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD((user + ":" + factor).getBytes(US_ASCII));
            return cipher;
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public static void enroll(Connection operator, UUID user, byte[] seed)
            throws SQLException, GeneralSecurityException, IOException {
        if (seed.length != 20) throw new IllegalArgumentException("Expected 160-bit seed");
        UUID factor = UUID.randomUUID();
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, user, factor, nonce).doFinal(seed);
        try (var insert = operator.prepareStatement("INSERT INTO tt.totp_factor(user_id,factor_id,ciphertext) VALUES (?,?,?)")) {
            insert.setObject(1, user);
            insert.setObject(2, factor);
            insert.setBytes(3, ByteBuffer.allocate(48).put(nonce).put(encrypted).array());
            insert.executeUpdate();
        }
    }

    public static String code(byte[] seed, long step) throws GeneralSecurityException {
        var mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(seed, "HmacSHA1"));
        byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
        int position = hash[hash.length - 1] & 15;
        int value = ByteBuffer.wrap(hash).getInt(position) & Integer.MAX_VALUE;
        return String.format(Locale.ROOT, "%06d", value % 1_000_000);
    }

    public static Proof verify(DataSource database, UUID user, UUID org, String input)
            throws SQLException, GeneralSecurityException, IOException {
        if (!input.matches("[0-9]{6}")) return null;
        UUID factor;
        byte[] sealed;
        long step;
        try (var connection = database.getConnection();
             var query = connection.prepareStatement("SELECT * FROM tt.start_totp(?,?)")) {
            query.setObject(1, user);
            query.setObject(2, org);
            try (var row = query.executeQuery()) {
                if (!row.next()) return null;
                factor = row.getObject(1, UUID.class);
                sealed = row.getBytes(2);
                step = row.getLong(3);
            }
        }
        byte[] seed = cipher(Cipher.DECRYPT_MODE, user, factor, Arrays.copyOf(sealed, 12))
                .doFinal(sealed, 12, sealed.length - 12);
        long matched = -1;
        try {
            for (long candidate = step - 1; candidate <= step + 1; candidate++) {
                if (MessageDigest.isEqual(code(seed, candidate).getBytes(US_ASCII), input.getBytes(US_ASCII))) {
                    matched = candidate;
                }
            }
        } finally {
            Arrays.fill(seed, (byte) 0);
        }
        if (matched < 0) return null;
        try (var connection = database.getConnection();
             var accept = connection.prepareStatement("SELECT tt.accept_totp(?,?,?,?)")) {
            accept.setObject(1, user);
            accept.setObject(2, org);
            accept.setObject(3, factor);
            accept.setLong(4, matched);
            try (var result = accept.executeQuery()) {
                result.next();
                var time = result.getTimestamp(1);
                return time == null ? null : new Proof(factor, time.toInstant());
            }
        }
    }
}
