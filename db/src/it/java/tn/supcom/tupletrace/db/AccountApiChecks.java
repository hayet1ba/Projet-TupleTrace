package tn.supcom.tupletrace.db;

import jakarta.json.Json;
import org.postgresql.ds.PGSimpleDataSource;
import tn.supcom.tupletrace.security.AccessTokens;
import tn.supcom.tupletrace.security.TotpFactor;
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
        if (expected != 0) assertEquals(expected, result.statusCode(), path);
        if (json != null) assertEquals("no-store", result.headers().firstValue("Cache-Control").orElse(""));
        return result;
    }

    private String login(String name, String supplied, int expected) throws Exception {
        return login(name, supplied, null, expected);
    }

    private HttpResponse<String> credentials(String name, String supplied, String otp, int expected) throws Exception {
        var json = Json.createObjectBuilder().add("email", name).add("password", supplied);
        if (otp != null) json.add("otp", otp);
        return request("/auth/login", json.build().toString(), null, expected);
    }

    private String login(String name, String supplied, String otp, int expected) throws Exception {
        String body = credentials(name, supplied, otp, expected).body();
        if (expected == 202) {
            try (var reader = Json.createReader(new StringReader(body))) {
                var challenge = reader.readObject();
                assertEquals(Set.of("next"), challenge.keySet());
                assertEquals("totp", challenge.getString("next"));
            }
            return null;
        }
        if (expected != 200) {
            assertTrue(body.isBlank());
            return null;
        }
        return accessToken(body);
    }

    private static String accessToken(String body) {
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

    private byte[] enroll(UUID account) throws Exception {
        byte[] seed = new byte[20];
        new java.security.SecureRandom().nextBytes(seed);
        TotpFactor.enroll(operator, account, seed);
        return seed;
    }

    private static long step() {
        return Instant.now().getEpochSecond() / 30;
    }

    private String concurrentLogin(String otp) throws Exception {
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.Callable<HttpResponse<String>> attempt = () -> {
                start.await();
                return credentials(email, password, otp, 0);
            };
            var first = pool.submit(attempt);
            var second = pool.submit(attempt);
            var a = first.get();
            var b = second.get();
            assertEquals(List.of(200,401), java.util.stream.Stream.of(a,b)
                    .map(HttpResponse::statusCode).sorted().toList());
            return accessToken((a.statusCode() == 200 ? a : b).body());
        }
    }

    private void checkEvent(HttpResponse<String> response, String kind, UUID actor, UUID organization, UUID target)
            throws SQLException {
        var correlation = UUID.fromString(response.headers().firstValue("X-Correlation-ID").orElseThrow());
        try (var stmt = operator.prepareStatement("""
                SELECT event_type,actor_id,organization_id,target_id FROM tt.audit_event WHERE correlation_id=?
                """)) {
            stmt.setObject(1, correlation);
            try (var row = stmt.executeQuery()) {
                assertTrue(row.next());
                assertEquals(kind, row.getString(1));
                assertEquals(actor, row.getObject(2, UUID.class));
                assertEquals(organization, row.getObject(3, UUID.class));
                assertEquals(target, row.getObject(4, UUID.class));
                assertFalse(row.next());
            }
        }
    }

    private void checkScope(String token) throws Exception {
        var foreign = request("/organizations/" + other, null, token, 404);
        var missing = request("/organizations/" + UUID.randomUUID(), null, token, 404);
        assertTrue(foreign.body().isEmpty() && missing.body().isEmpty());
        assertEquals("no-store", foreign.headers().firstValue("Cache-Control").orElseThrow());
        checkEvent(foreign, "IAM_ORGANIZATION_NOT_FOUND", user, org, null);
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
        checkEvent(request("/private", null, token, 403), "IAM_AUTHORIZATION_DENIED", user, org, null);
        checkScope(token);
        // Cette URL accepte toute identité au niveau servlet. L'EJB impose seul le rôle AUDITEUR.
        request(mine, null, token, 403);
        request("/auth/factor/revoke", "{}", token, 403);
        update(grant, org, user);
        for (String role : List.of("ADMINISTRATEUR", "PROPRIETAIRE", "AUDITEUR")) {
            update("UPDATE tt.role_assignment SET role_name=? WHERE user_id=?", role, user);
            request(mine, null, token, 401);
            login(email, password, 202);
            login(email, password, "000000", 401);
        }
        byte[] seed = enroll(user);
        long counter = step();
        // Pick a historical code distinct from the three currently admissible values.
        long old = counter - 5;
        var current = List.of(TotpFactor.code(seed,counter-1), TotpFactor.code(seed,counter), TotpFactor.code(seed,counter+1));
        while (current.contains(TotpFactor.code(seed,old))) old--;
        login(email, password, TotpFactor.code(seed,old), 401);
        String wrong = "000000";
        while (current.contains(wrong)) wrong = String.format(Locale.ROOT, "%06d", Integer.parseInt(wrong) + 1);
        login(email, password, wrong, 401);
        login(email, password, 202);
        String accepted = TotpFactor.code(seed, step());
        token = concurrentLogin(accepted);
        login(email, password, accepted, 401);
        request("/private", null, token, 200);
        var metadata = request(mine, null, token, 200);
        try (var reader = Json.createReader(new StringReader(metadata.body()))) {
            assertEquals(org.toString(), reader.readObject().getString("id"));
        }
        checkScope(token);
        var colleagueSeed = enroll(colleague);
        String wrongCode = "000000";
        long colleagueStep = step();
        var possible = new HashSet<String>();
        for (long delta = -1; delta <= 2; delta++) possible.add(TotpFactor.code(colleagueSeed, colleagueStep + delta));
        while (possible.contains(wrongCode)) wrongCode = String.format(Locale.ROOT, "%06d", Integer.parseInt(wrongCode) + 1);
        for (int i = 0; i < 5; i++) login(colleagueEmail, colleaguePassword, wrongCode, 401);
        login(colleagueEmail, colleaguePassword, TotpFactor.code(colleagueSeed, step()), 401);
        update("UPDATE tt.totp_factor SET window_start=now()-interval '5 minutes' WHERE user_id=?", colleague);
        String otherToken = login(colleagueEmail, colleaguePassword, TotpFactor.code(colleagueSeed,step()), 200);
        request("/organizations/" + other, null, otherToken, 200);
        request(mine, null, otherToken, 404);
        request("/private", null, null, 401);
        for (String role : List.of("PROPRIETAIRE", "ADMINISTRATEUR", "GESTIONNAIRE", "ENQUETEUR", "DESTINATAIRE")) {
            update("UPDATE tt.role_assignment SET role_name=? WHERE user_id=?", role, user);
            request("/private", null, token, 403);
            checkEvent(request(mine, null, token, 403), "IAM_AUTHORIZATION_DENIED", user, org, null);
            checkScope(token);
        }
        update("UPDATE tt.role_assignment SET role_name='AUDITEUR' WHERE user_id=?", user);
        request(mine, null, token, 200);
        update("DELETE FROM tt.role_assignment WHERE user_id=?", user);
        request(mine, null, token, 403);
        update("UPDATE tt.app_user SET organization_id=? WHERE id=?", other,user);
        request("/private", null, token, 401);
        update("UPDATE tt.app_user SET organization_id=? WHERE id=?", org,user);
        update(grant, org, user);
        update("UPDATE tt.app_user SET status='SUSPENDED' WHERE id=?", user);
        login(email, password, TotpFactor.code(seed, step() + 1), 401);
        request(mine, null, token, 401);
        update("UPDATE tt.app_user SET status='ACTIVE' WHERE id=?", user);
        update("UPDATE tt.organization SET status='SUSPENDED' WHERE id=?", org);
        login(email, password, TotpFactor.code(seed, step() + 1), 401);
        request(mine, null, token, 401);
        update("UPDATE tt.organization SET status='ACTIVE' WHERE id=?", org);
        var identity = tokens(Clock.systemUTC()).verify(token);
        assertEquals(user, identity.subject());
        assertEquals(org, identity.organization());
        String second = login(email, password, TotpFactor.code(seed, step() + 1), 200);
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
        var stale = new TotpFactor.Proof(identity.proof().factor(), Instant.now().minusSeconds(301));
        var staleToken = tokens(Clock.systemUTC()).issue(user,org,stale);
        request("/private", null, staleToken, 200);
        request("/auth/factor/revoke", "{}", staleToken, 403);
        var service = tokens(Clock.systemUTC());
        var staleIdentity = service.verify(staleToken);
        var foreignIdentity = service.verify(otherToken);
        String foreignSelector = Json.createObjectBuilder().add("user", colleague.toString())
                .add("organization", other.toString()).add("jti", foreignIdentity.tokenId().toString())
                .add("factor_id", foreignIdentity.proof().factor().toString()).build().toString();
        checkEvent(request("/auth/logout", foreignSelector, staleToken, 204), "IAM_LOGOUT", user, org,
                staleIdentity.tokenId());
        request("/auth/logout", "{}", staleToken, 401);
        request("/private", null, staleToken, 401);
        request("/organizations/" + other, null, staleToken, 401);
        request("/private", null, second, 200);
        // A failure to append the audit event must roll back the factor revocation.
        String audit = "tt.append_audit_event(uuid,uuid,text,uuid,uuid)";
        update("REVOKE EXECUTE ON FUNCTION " + audit + " FROM tt_app");
        try {
            assertTrue(request("/auth/logout", "{}", second, 500).body().isBlank());
            request(mine, null, second, 200);
            assertTrue(request("/auth/factor/revoke", "{}", second, 500).body().isBlank());
        } finally {
            update("GRANT EXECUTE ON FUNCTION " + audit + " TO tt_app");
        }
        request(mine, null, second, 200);
        var revoked = request("/auth/factor/revoke", foreignSelector, second, 204);
        checkEvent(revoked, "IAM_FACTOR_REVOKED", user, org, identity.proof().factor());
        request("/organizations/" + other, null, otherToken, 200);
        try (var stmt = operator.prepareStatement("SELECT count(*) FROM tt.totp_factor WHERE user_id=?")) {
            stmt.setObject(1, user);
            try (var row = stmt.executeQuery()) {
                row.next();
                assertEquals(0, row.getInt(1), "Factor deletion (HTTP " + revoked.statusCode() + ")");
            }
        }
        assertEquals(204, revoked.statusCode());
        request("/private", null, second, 401);
        login(email, password, 202);
        login(email, password, TotpFactor.code(seed,step()), 401);
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
