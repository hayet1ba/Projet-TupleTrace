package tn.supcom.tupletrace.security;

import jakarta.json.Json;
import jakarta.json.stream.JsonParser;

import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.EdECPublicKey;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class TokenProfile {
    static final Duration LIFETIME = Duration.ofMinutes(15);
    private static final Set<String> CLAIMS = Set.of("iss", "aud", "sub", "iat", "nbf", "exp", "jti");
    private final String issuer;
    private final String audience;

    TokenProfile(String issuer, String audience) {
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("Issuer and audience required");
        }
        this.issuer = issuer;
        this.audience = audience;
    }

    String issue(UUID subject, Instant now, UUID kid, EdECPrivateKey key) throws GeneralSecurityException {
        return issue(subject, null, now, kid, key);
    }

    String issue(UUID subject, UUID organization, Instant now, UUID kid, EdECPrivateKey key) throws GeneralSecurityException {
        return issue(subject, organization, now, kid, key, null);
    }

    String issue(UUID subject, UUID organization, Instant now, UUID kid, EdECPrivateKey key, TotpFactor.Proof proof)
            throws GeneralSecurityException {
        var payload = Json.createObjectBuilder().add("iss", issuer).add("aud", audience)
                .add("sub", subject.toString()).add("iat", now.toString()).add("nbf", now.toString())
                .add("exp", now.plus(LIFETIME).toString()).add("jti", UUID.randomUUID().toString());
        if (organization != null) payload.add("org", organization.toString());
        if (proof != null) {
            if (organization == null) throw invalid();
            // Use the earlier clock reading so clock skew can only shorten the step-up window.
            Instant strongAt = proof.time().isBefore(now) ? proof.time() : now;
            payload.add("factor_id", proof.factor().toString()).add("auth_time", strongAt.toString())
                    .add("amr", Json.createArrayBuilder().add("pwd").add("otp"));
        }
        return V4Public.sign(key, payload.build().toString(), footer(kid));
    }

    private static String footer(UUID kid) {
        return "{\"kid\":\"" + kid + "\"}";
    }

    static UUID keyId(String token) {
        if (token == null || token.length() > 8192) throw invalid();
        String[] parts = token.split("\\.", -1);
        if (parts.length != 4 || !parts[0].equals("v4") || !parts[1].equals("public")) throw invalid();
        byte[] body = decode(parts[2]);
        if (body.length <= 64) throw invalid();
        utf8(Arrays.copyOf(body, body.length - 64));
        String footer = utf8(decode(parts[3]));
        var fields = strings(footer, Set.of("kid"), Set.of());
        UUID kid = uuid(fields.get("kid"));
        if (!footer.equals(footer(kid))) throw invalid();
        return kid;
    }

    AccessTokens.Identity verify(String token, UUID kid, EdECPublicKey key, Instant now) {
        String payload;
        try {
            payload = V4Public.verify(key, token, footer(kid), "");
        } catch (GeneralSecurityException | RuntimeException failure) {
            throw invalid();
        }
        var fields = strings(payload, CLAIMS, Set.of("org", "amr", "auth_time", "factor_id"));
        if (!issuer.equals(fields.get("iss")) || !audience.equals(fields.get("aud"))) throw invalid();
        try {
            Instant issued = Instant.parse(fields.get("iat"));
            Instant start = Instant.parse(fields.get("nbf"));
            Instant end = Instant.parse(fields.get("exp"));
            if (issued.isAfter(now) || start.isBefore(issued) || start.isAfter(now)
                    || !end.isAfter(now) || Duration.between(issued, end).compareTo(LIFETIME) > 0) throw invalid();
            TotpFactor.Proof proof = null;
            if (fields.containsKey("amr") || fields.containsKey("auth_time") || fields.containsKey("factor_id")) {
                if (!fields.keySet().containsAll(Set.of("amr", "auth_time", "factor_id", "org"))) throw invalid();
                Instant time = Instant.parse(fields.get("auth_time"));
                if (time.isAfter(issued)) throw invalid();
                proof = new TotpFactor.Proof(uuid(fields.get("factor_id")), time);
            }
            return new AccessTokens.Identity(uuid(fields.get("sub")), uuid(fields.get("jti")), end,
                    fields.containsKey("org") ? uuid(fields.get("org")) : null, proof);
        } catch (DateTimeException failure) {
            throw invalid();
        }
    }

    private static Map<String, String> strings(String json, Set<String> required, Set<String> optional) {
        var values = new HashMap<String, String>();
        try (var parser = Json.createParser(new StringReader(json))) {
            if (parser.next() != JsonParser.Event.START_OBJECT) throw invalid();
            for (var event = parser.next(); event != JsonParser.Event.END_OBJECT; event = parser.next()) {
                if (event != JsonParser.Event.KEY_NAME) throw invalid();
                String name = parser.getString();
                if ((!required.contains(name) && !optional.contains(name)) || values.containsKey(name)) throw invalid();
                if ("amr".equals(name)) {
                    if (parser.next() != JsonParser.Event.START_ARRAY
                            || parser.next() != JsonParser.Event.VALUE_STRING || !"pwd".equals(parser.getString())
                            || parser.next() != JsonParser.Event.VALUE_STRING || !"otp".equals(parser.getString())
                            || parser.next() != JsonParser.Event.END_ARRAY) throw invalid();
                    values.put(name, "otp");
                } else {
                    if (parser.next() != JsonParser.Event.VALUE_STRING) throw invalid();
                    values.put(name, parser.getString());
                }
            }
            if (parser.hasNext() || !values.keySet().containsAll(required)) throw invalid();
        } catch (RuntimeException failure) {
            throw invalid();
        }
        return values;
    }

    private static UUID uuid(String text) {
        try {
            UUID id = UUID.fromString(text);
            if (!id.toString().equals(text)) throw invalid();
            return id;
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static byte[] decode(String encoded) {
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded)) throw invalid();
        return bytes;
    }

    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw invalid();
        }
    }

    static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid access token");
    }
}
