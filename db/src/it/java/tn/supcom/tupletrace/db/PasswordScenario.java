package tn.supcom.tupletrace.db;

import tn.supcom.tupletrace.security.PasswordAccounts;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;
import static tn.supcom.tupletrace.security.PasswordAccounts.Status.*;

final class PasswordScenario {
    private final PasswordAccounts service;
    private final Connection admin;
    private final String login = UUID.randomUUID() + "@example.invalid";
    private final char[] password = UUID.randomUUID().toString().toCharArray();

    private PasswordScenario(PasswordAccounts service, Connection admin) {
        this.service = service;
        this.admin = admin;
    }

    static void verify(PasswordAccounts service, Connection admin) throws Exception {
        var test = new PasswordScenario(service, admin);
        try {
            test.checkIdentities();
            test.checkThrottle();
        } finally {
            Arrays.fill(test.password, '\0');
        }
    }

    private String value(String sql, Object... parameters) throws SQLException {
        try (var query = admin.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) query.setObject(i + 1, parameters[i]);
            try (var row = query.executeQuery()) {
                assertTrue(row.next());
                return row.getString(1);
            }
        }
    }

    private PasswordAccounts.Result attempt(String email, char[] supplied, String address) throws Exception {
        return service.verify(email, supplied, InetAddress.getByName(address), UUID.randomUUID());
    }

    private void checkIdentities() throws Exception {
        UUID org = UUID.fromString(value("INSERT INTO tt.organization(name,status) VALUES ('Password IT','ACTIVE') RETURNING id"));
        String addUser = "INSERT INTO tt.app_user(organization_id,email,status) VALUES (?,?,'ACTIVE') RETURNING id";
        UUID user = UUID.fromString(value(addUser, org, login));
        UUID second = UUID.fromString(value(addUser, org, "second-" + login));
        var refused = new PasswordAccounts.Result(DENIED, null);
        String peer = "192.0.2.1";
        assertEquals(refused, attempt(login, password, peer));
        PasswordAccounts.enroll(admin, user, password);
        PasswordAccounts.enroll(admin, second, password);
        assertEquals("2", value("SELECT count(DISTINCT salt) FROM tt.password_credential WHERE user_id IN (?,?)", user, second));
        assertEquals("2", value("SELECT count(DISTINCT verifier) FROM tt.password_credential WHERE user_id IN (?,?)", user, second));
        assertEquals("23505", assertThrows(SQLException.class, () -> PasswordAccounts.enroll(admin, user, password)).getSQLState());

        assertEquals(new PasswordAccounts.Result(VERIFIED, user), attempt(" " + login.toUpperCase(Locale.ROOT) + " ", password, peer));
        assertEquals(refused, attempt(login, UUID.randomUUID().toString().toCharArray(), peer));
        assertEquals(refused, attempt("unknown-" + login, password, peer));
        for (String state : List.of("SUSPENDED","INVITED","CLOSED")) {
            value("UPDATE tt.app_user SET status=? WHERE id=? RETURNING id", state, second);
            assertEquals(refused, attempt("second-" + login, password, peer));
        }
        value("UPDATE tt.organization SET status='SUSPENDED' WHERE id=? RETURNING id", org);
        UUID correlation = UUID.randomUUID();
        assertEquals(refused, service.verify(login, password, InetAddress.getByName(peer), correlation));
        assertEquals("1", value("SELECT count(*) FROM tt.audit_event WHERE correlation_id=? AND event_type='PASSWORD_CHECK_DENIED' "
                + "AND actor_id IS NULL AND target_id IS NULL", correlation));
        value("UPDATE tt.organization SET status='ACTIVE' WHERE id=? RETURNING id", org);
    }

    private void checkThrottle() throws Exception {
        // Échecs dans la fenêtre, mais suffisamment anciens pour avoir dépassé le délai précédent.
        String seed = """
                INSERT INTO tt.login_failure(occurred_at,account_key,origin)
                SELECT clock_timestamp()-interval '2 minutes',sha256(convert_to(?, 'UTF8')),?::inet
                FROM generate_series(1,?) RETURNING id
                """;
        for (String identifier : List.of(login, "absent-" + login)) {
            // Quatre échecs autorisent encore la vérification, le cinquième déclenche le délai.
            try (var clear = admin.prepareStatement("DELETE FROM tt.login_failure WHERE account_key=sha256(convert_to(?, 'UTF8'))")) {
                clear.setString(1, identifier);
                clear.executeUpdate();
            }
            value(seed, identifier, "203.0.113.9", 4);
            assertEquals(DENIED, attempt(identifier, new char[0], "203.0.113.10").status());
            assertEquals("5", value("SELECT count(*) FROM tt.login_failure WHERE account_key=sha256(convert_to(?, 'UTF8'))", identifier));
            assertEquals(DENIED, attempt(identifier, password, "203.0.113.11").status());
            assertEquals("5", value("SELECT count(*) FROM tt.login_failure WHERE account_key=sha256(convert_to(?, 'UTF8'))", identifier));
        }
        value("UPDATE tt.login_failure SET occurred_at=clock_timestamp()-interval '16 minutes' "
                + "WHERE account_key=sha256(convert_to(?, 'UTF8')) RETURNING id", login);
        value(seed, login, "192.0.2.2", 10);
        assertEquals(DENIED, attempt(login, new char[0], "192.0.2.3").status());
        assertEquals(DENIED, attempt(login, password, "192.0.2.4").status());
        assertEquals("1", value("SELECT count(*) FROM tt.login_failure WHERE origin='192.0.2.4' AND account_key IS NULL"));

        // Deux connexions indépendantes se disputent la dernière tentative de l'origine.
        String peer = "198.51.100.10";
        value(seed, "seed-" + login, peer, 19);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return attempt("a-" + login, password, peer).status(); });
            var b = pool.submit(() -> { start.await(); return attempt("b-" + login, password, peer).status(); });
            start.countDown();
            var outcomes = List.of(a.get(), b.get());
            assertTrue(outcomes.containsAll(List.of(DENIED, RATE_LIMITED)));
        }
        value("UPDATE tt.login_failure SET occurred_at=clock_timestamp()-interval '16 minutes' "
                + "WHERE origin=?::inet OR account_key=sha256(convert_to(?, 'UTF8')) RETURNING id", peer, login);
        assertEquals(VERIFIED, attempt(login, password, peer).status());
    }
}
