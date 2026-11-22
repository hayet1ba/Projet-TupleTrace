package tn.supcom.tupletrace.security;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Services internes pour le périmètre des jetons d'accès de la plateforme. */
public final class AccessTokens {
    public record Identity(UUID subject, UUID tokenId, Instant expiresAt, UUID organization) {}

    private static final String SELECT = """
            SELECT key_id FROM tt.signing_key_version
            WHERE purpose='ACCESS_TOKEN' AND organization_id IS NULL
              AND store_alias = 'paseto-' || key_id::text AND
            """;
    private static final String USABLE =
            "(status='ACTIVE' OR (status='VERIFY_ONLY' AND verify_until > statement_timestamp()))";
    private final DataSource database;
    private final AccessKeyStore keys;
    private final TokenProfile profile;
    private final Clock clock;

    public AccessTokens(DataSource database, Path directory, Supplier<char[]> password,
                        String issuer, String audience, Clock clock) {
        this.database = database;
        keys = new AccessKeyStore(directory, password);
        profile = new TokenProfile(issuer, audience);
        this.clock = clock;
    }

    public String issue(UUID subject) throws SQLException, IOException, GeneralSecurityException {
        return issue(subject, null);
    }

    public String issue(UUID subject, UUID organization) throws SQLException, IOException, GeneralSecurityException {
        Instant now = clock.instant();
        try (var connection = database.getConnection();
             var query = connection.prepareStatement(SELECT + "status='ACTIVE'"); var rows = query.executeQuery()) {
            if (!rows.next()) throw new IllegalStateException("No active access key");
            UUID kid = rows.getObject(1, UUID.class);
            return profile.issue(subject, organization, now, kid, keys.signingKey(kid));
        }
    }

    public Identity verify(String token) throws SQLException, IOException, GeneralSecurityException {
        UUID kid = TokenProfile.keyId(token);
        try (var connection = database.getConnection();
             var query = connection.prepareStatement(SELECT + USABLE + " AND key_id=?")) {
            query.setObject(1, kid);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) throw TokenProfile.invalid();
            }
        }
        var identity = profile.verify(token, kid, keys.publicKey(kid), clock.instant());
        try (var connection = database.getConnection();
             var query = connection.prepareStatement("SELECT 1 FROM tt.revoked_access_token WHERE token_id=?")) {
            query.setObject(1, identity.tokenId());
            try (var rows = query.executeQuery()) {
                if (rows.next()) throw TokenProfile.invalid();
            }
        }
        return identity;
    }

    public Map<String, List<Map<String, String>>> jwks() throws SQLException, IOException, GeneralSecurityException {
        var publicKeys = new ArrayList<Map<String, String>>();
        try (var connection = database.getConnection();
             var query = connection.prepareStatement(SELECT + USABLE + " ORDER BY version"); var rows = query.executeQuery()) {
            while (rows.next()) {
                UUID kid = rows.getObject(1, UUID.class);
                publicKeys.add(Map.of("kid", kid.toString(), "kty", "OKP", "crv", "Ed25519",
                        "x", AccessKeyStore.coordinate(keys.publicKey(kid))));
            }
        }
        return Map.of("keys", List.copyOf(publicKeys));
    }
}
