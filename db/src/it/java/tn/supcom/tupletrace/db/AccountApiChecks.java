package tn.supcom.tupletrace.db;

import jakarta.json.Json;
import org.postgresql.ds.PGSimpleDataSource;
import tn.supcom.tupletrace.security.AccessTokens;
import tn.supcom.tupletrace.security.PasswordAccounts;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.security.KeyStore;
import java.sql.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Vérifications HTTPS après déploiement sur le serveur et la base jetables du job build. */
public final class AccountApiChecks {
    private final Connection operator;
    private final HttpClient client;
    private final UUID user = UUID.randomUUID(), org = UUID.randomUUID(), other = UUID.randomUUID();
    private final String email = user + "@example.invalid";
    private final String password = UUID.randomUUID().toString();

    private AccountApiChecks(Connection operator, HttpClient client) {
        this.operator = operator;
        this.client = client;
    }

    public static void main(String[] args) throws Exception {
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(KeyStore.getInstance(Path.of(args[0]).toFile(), (char[]) null));
        var tls = SSLContext.getInstance("TLS");
        tls.init(null, trust.getTrustManagers(), null);
        var client = HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(5)).build();
        try (var db = DriverManager.getConnection(System.getenv("TT_JDBC_URL"), "tt_migrator",
                System.getenv("TT_MIGRATION_PASSWORD")); var check = db.createStatement();
             var row = check.executeQuery("SELECT current_database()")) {
            assertTrue(row.next());
            assertEquals("tupletrace_test", row.getString(1));
            new AccountApiChecks(db, client).run();
        }
        System.out.println("Contrôles HTTPS des comptes, des rôles, des organisations et des jetons réussis.");
    }

    private void update(String sql, Object... values) throws SQLException {
        try (var stmt = operator.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) stmt.setObject(i + 1, values[i]);
            stmt.executeUpdate();
        }
    }

    private HttpResponse<String> request(String path, String json, String bearer, int expected) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("https://localhost:8443/api" + path)).timeout(Duration.ofSeconds(10));
        if (json != null) req.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
        if (bearer != null) req.header("Authorization", "Bearer " + bearer);
        var result = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, result.statusCode(), path);
        if (json != null) assertEquals("no-store", result.headers().firstValue("Cache-Control").orElse(""));
        return result;
    }

    private String login(String name, String supplied, int expected) throws Exception {
        String json = Json.createObjectBuilder().add("email", name).add("password", supplied).build().toString();
        String body = request("/auth/login", json, null, expected).body();
        if (expected != 200) {
            assertTrue(body.isBlank());
            return null;
        }
        try (var reader = Json.createReader(new StringReader(body))) {
            var response = reader.readObject();
            assertEquals("Bearer", response.getString("token_type"));
            return response.getString("access_token");
        }
    }

    private AccessTokens tokens(Clock clock) {
        var ds = new PGSimpleDataSource();
        ds.setUrl(System.getenv("TT_JDBC_URL"));
        ds.setUser("tt_app");
        ds.setPassword(System.getenv("TT_APP_PASSWORD"));
        return new AccessTokens(ds, Path.of(System.getenv("TT_KEY_DIR")),
                () -> System.getenv("TT_KEYSTORE_PASSWORD").toCharArray(),
                System.getenv("TT_TOKEN_ISSUER"), System.getenv("TT_TOKEN_AUDIENCE"), clock);
    }

    private void run() throws Exception {
        update("INSERT INTO tt.organization(id,name,status) VALUES (?,'A','ACTIVE'),(?,'B','ACTIVE')", org, other);
        update("INSERT INTO tt.app_user(id,email,organization_id,status) VALUES (?,?,?,'ACTIVE')", user,email,org);
        PasswordAccounts.enroll(operator, user, password.toCharArray());
        String grant = "INSERT INTO tt.role_assignment(organization_id,user_id,role_name) VALUES (?,?,'AUDITEUR')";
        UUID colleague = UUID.randomUUID();
        String colleagueEmail = colleague + "@example.invalid";
        String colleaguePassword = UUID.randomUUID().toString();
        update("INSERT INTO tt.app_user(id,email,organization_id,status) VALUES (?,?,?,'ACTIVE')",
                colleague, colleagueEmail, other);
        PasswordAccounts.enroll(operator, colleague, colleaguePassword.toCharArray());
        update(grant, other, colleague);
        login(email, UUID.randomUUID().toString(), 401);
        login("unknown-" + email, password, 401);
        request("/auth/login", "{\"email\":\"x\",\"password\":\"x\",\"roles\":[\"AUDITEUR\"]}", null, 400);
        String token = login(email, password, 200);
        String mine = "/organizations/" + org;
        request(mine, null, null, 401);
        request("/private", null, token, 403);
        // Cette URL accepte toute identité au niveau servlet. L'EJB impose seul le rôle AUDITEUR.
        request(mine, null, token, 403);
        update(grant, org, user);
        request("/private", null, token, 200);
        var metadata = request(mine, null, token, 200);
        try (var reader = Json.createReader(new StringReader(metadata.body()))) {
            assertEquals(org.toString(), reader.readObject().getString("id"));
        }
        request("/organizations/" + other, null, token, 404);
        String otherToken = login(colleagueEmail, colleaguePassword, 200);
        request("/organizations/" + other, null, otherToken, 200);
        request(mine, null, otherToken, 404);
        request("/private", null, null, 401);
        update("UPDATE tt.role_assignment SET role_name='DESTINATAIRE' WHERE user_id=?", user);
        request(mine, null, token, 403);
        update("UPDATE tt.role_assignment SET role_name='AUDITEUR' WHERE user_id=?", user);
        request(mine, null, token, 200);
        update("DELETE FROM tt.role_assignment WHERE user_id=?", user);
        request(mine, null, token, 403);
        update("UPDATE tt.app_user SET organization_id=? WHERE id=?", other,user);
        request("/private", null, token, 401);
        update("UPDATE tt.app_user SET organization_id=? WHERE id=?", org,user);
        update(grant, org, user);
        update("UPDATE tt.app_user SET status='SUSPENDED' WHERE id=?", user);
        login(email, password, 401);
        request(mine, null, token, 401);
        update("UPDATE tt.app_user SET status='ACTIVE' WHERE id=?", user);
        update("UPDATE tt.organization SET status='SUSPENDED' WHERE id=?", org);
        login(email, password, 401);
        request(mine, null, token, 401);
        update("UPDATE tt.organization SET status='ACTIVE' WHERE id=?", org);
        var identity = tokens(Clock.systemUTC()).verify(token);
        assertEquals(user, identity.subject());
        assertEquals(org, identity.organization());
        String second = login(email, password, 200);
        update("INSERT INTO tt.revoked_access_token(token_id,expires_at) VALUES (?,?)",
                identity.tokenId(), Timestamp.from(identity.expiresAt()));
        request("/private", null, token, 401);
        request("/private", null, second, 200);
        var past = tokens(Clock.offset(Clock.systemUTC(), Duration.ofMinutes(-16)));
        request("/private", null, past.issue(user, org), 401);
        request("/private", null, tokens(Clock.systemUTC()).issue(user), 401);
        String[] parts = second.split("\\.");
        byte[] signed = Base64.getUrlDecoder().decode(parts[2]);
        signed[signed.length - 1] ^= 1;
        parts[2] = Base64.getUrlEncoder().withoutPadding().encodeToString(signed);
        request("/private", null, String.join(".", parts), 401);
        for (int n = 0; n < 21; n++) {
            String json = Json.createObjectBuilder().add("email", UUID.randomUUID()+"@example.invalid")
                    .add("password", password).build().toString();
            var req = HttpRequest.newBuilder(URI.create("https://localhost:8443/api/auth/login"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
            var response = client.send(req, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 429) {
                assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
                return;
            }
            assertEquals(401, response.statusCode());
        }
        fail("Quota par origine non appliqué");
    }
}
