package tn.supcom.tupletrace.security;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TokenProfileTest {
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private final TokenProfile profile = new TokenProfile("issuer", "audience");
    private final UUID kid = UUID.randomUUID();
    private final UUID sub = UUID.randomUUID();
    private final KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

    TokenProfileTest() throws Exception {}

    private EdECPrivateKey privateKey() { return (EdECPrivateKey) key.getPrivate(); }
    private EdECPublicKey publicKey() { return (EdECPublicKey) key.getPublic(); }

    @Test
    void acceptsCurrentTokenAndRejectsTampering() throws Exception {
        String token = profile.issue(sub, NOW, kid, privateKey());
        assertEquals(kid, TokenProfile.keyId(token));
        assertEquals(sub, profile.verify(token, kid, publicKey(), NOW).subject());
        String[] fields = token.split("\\.");
        byte[] signed = Base64.getUrlDecoder().decode(fields[2]);
        signed[signed.length - 1] ^= 1;
        fields[2] = Base64.getUrlEncoder().withoutPadding().encodeToString(signed);
        assertThrows(IllegalArgumentException.class, () -> profile.verify(String.join(".", fields), kid, publicKey(), NOW));
        UUID other = UUID.randomUUID();
        String changedFooter = profile.issue(sub, NOW, other, privateKey()).split("\\.")[3];
        String swapped = token.substring(0, token.lastIndexOf('.') + 1) + changedFooter;
        assertThrows(IllegalArgumentException.class, () -> profile.verify(swapped, other, publicKey(), NOW));
    }

    @ParameterizedTest
    @ValueSource(strings = {"iss", "aud", "sub", "jti", "iat", "nbf", "exp", "overlong"})
    void rejectsInvalidSignedClaims(String field) throws Exception {
        var claims = Json.createObjectBuilder().add("iss", "issuer").add("aud", "audience")
                .add("sub", sub.toString()).add("jti", UUID.randomUUID().toString())
                .add("iat", NOW.minusSeconds(30).toString()).add("nbf", NOW.minusSeconds(30).toString())
                .add("exp", NOW.plusSeconds(10).toString());
        switch (field) {
            case "iat", "nbf" -> claims.add(field, NOW.plusSeconds(1).toString());
            case "exp" -> claims.add(field, NOW.toString());
            case "overlong" -> claims.add("exp", NOW.plusSeconds(901).toString());
            default -> claims.add(field, "invalid");
        }
        String token = V4Public.sign(privateKey(), claims.build().toString(), "{\"kid\":\"" + kid + "\"}");
        assertThrows(IllegalArgumentException.class, () -> profile.verify(token, kid, publicKey(), NOW));
    }

    @ParameterizedTest
    @ValueSource(strings = {"amr", "auth_time", "factor_id", "org", "future", "methods"})
    void rejectsIncompleteOrInconsistentStrongAuthentication(String mutation) throws Exception {
        var claims = Json.createObjectBuilder().add("iss", "issuer").add("aud", "audience")
                .add("sub", sub.toString()).add("jti", UUID.randomUUID().toString())
                .add("iat", NOW.toString()).add("nbf", NOW.toString()).add("exp", NOW.plusSeconds(60).toString())
                .add("org", UUID.randomUUID().toString()).add("factor_id", UUID.randomUUID().toString())
                .add("amr", Json.createArrayBuilder().add("pwd").add("otp")).add("auth_time", NOW.toString());
        switch (mutation) {
            case "future" -> claims.add("auth_time", NOW.plusSeconds(10).toString());
            case "methods" -> claims.add("amr", "otp");
            default -> claims.remove(mutation);
        }
        var token = V4Public.sign(privateKey(), claims.build().toString(), "{\"kid\":\"" + kid + "\"}");
        assertThrows(IllegalArgumentException.class, () -> profile.verify(token, kid, publicKey(), NOW));
    }

    @Test
    void verifiesAllThreeOfficialPublicVectors() throws Exception {
        try (var reader = Json.createReader(getClass().getResourceAsStream("/paseto-vectors/public.json"))) {
            var vectors = reader.readArray().getValuesAs(JsonObject.class);
            assertEquals(3, vectors.size());
            for (var v : vectors) {
                byte[] spki = HexFormat.of().parseHex("302a300506032b6570032100" + v.getString("public-key"));
                var pub = (EdECPublicKey) KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
                assertArrayEquals(HexFormat.of().parseHex(v.getString("public-key")),
                        Base64.getUrlDecoder().decode(AccessKeyStore.coordinate(pub)));
                assertEquals(v.getString("payload"), V4Public.verify(pub, v.getString("token"),
                        v.getString("footer"), v.getString("implicit-assertion")), v.getString("name"));
            }
        }
    }
}
