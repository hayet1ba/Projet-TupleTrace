package tn.supcom.tupletrace.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.EdECPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Supplier;

final class AccessKeyStore {
    private final Path directory;
    private final Supplier<char[]> password;

    AccessKeyStore(Path directory, Supplier<char[]> password) {
        if (!directory.isAbsolute()) throw new IllegalArgumentException("Absolute key directory required");
        this.directory = directory;
        this.password = password;
    }

    private KeyStore load(UUID kid, char[] secret) throws IOException, GeneralSecurityException {
        if (secret.length == 0) throw new IllegalStateException("Keystore password required");
        var store = KeyStore.getInstance("PKCS12");
        try (var stream = Files.newInputStream(directory.resolve("paseto-" + kid + ".p12"))) {
            store.load(stream, secret);
        }
        return store;
    }

    EdECPrivateKey signingKey(UUID kid) throws IOException, GeneralSecurityException {
        char[] secret = java.util.Objects.requireNonNull(password.get(), "Keystore password required");
        try {
            var key = load(kid, secret).getKey("paseto-" + kid, secret);
            if (key instanceof EdECPrivateKey ed && ed.getParams().getName().equals("Ed25519")) return ed;
            throw new GeneralSecurityException("Expected Ed25519 signing key");
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    EdECPublicKey publicKey(UUID kid) throws IOException, GeneralSecurityException {
        char[] secret = java.util.Objects.requireNonNull(password.get(), "Keystore password required");
        try {
            var cert = load(kid, secret).getCertificate("paseto-" + kid);
            if (cert != null && cert.getPublicKey() instanceof EdECPublicKey ed
                    && ed.getParams().getName().equals("Ed25519")) return ed;
            throw new GeneralSecurityException("Expected Ed25519 verification key");
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    static String coordinate(EdECPublicKey key) {
        // RFC 8032 : y en little-endian sur 32 octets, avec la parité de x dans le bit haut.
        byte[] y = key.getPoint().getY().toByteArray();
        byte[] raw = new byte[32];
        for (int i = 0; i < y.length; i++) raw[i] = y[y.length - 1 - i];
        if (key.getPoint().isXOdd()) raw[31] |= (byte) 0x80;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
