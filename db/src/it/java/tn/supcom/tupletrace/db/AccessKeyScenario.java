package tn.supcom.tupletrace.db;

import org.postgresql.ds.PGSimpleDataSource;
import tn.supcom.tupletrace.security.AccessTokens;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class AccessKeyScenario {
    private final PGSimpleDataSource runtime = new PGSimpleDataSource();
    private final String password = UUID.randomUUID().toString();
    private final Path directory;
    private final URI uri;

    private AccessKeyScenario(String url, String appPassword, Path directory) {
        this.directory = directory;
        uri = URI.create(url.substring("jdbc:".length()));
        runtime.setUrl(url);
        runtime.setUser("tt_app");
        runtime.setPassword(appPassword);
    }

    private Process operator(String... arguments) throws Exception {
        String python = System.getProperty("os.name").startsWith("Windows") ? "python" : "python3";
        var command = new ArrayList<>(List.of(python, "../ops/keys/manage.py"));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true);
        var env = process.environment();
        env.put("TT_KEY_DIR", directory.toString());
        env.put("TT_KEYSTORE_PASSWORD", password);
        env.put("PGHOST", uri.getHost());
        env.put("PGPORT", Integer.toString(uri.getPort() < 0 ? 5432 : uri.getPort()));
        env.put("PGDATABASE", "tupletrace_test");
        return process.start();
    }

    private AccessTokens service() {
        return new AccessTokens(runtime, directory, password::toCharArray, "test-issuer", "test-api", Clock.systemUTC());
    }

    static void check(String url, String appPassword, Path store, Connection admin) throws Exception {
        var test = new AccessKeyScenario(url, appPassword, store);
        try (var sql = admin.createStatement()) {
            try {
                test.exercise(admin);
            } finally {
                // La suite entière exige une base tupletrace_test initialement vide.
                sql.executeUpdate("DELETE FROM tt.signing_key_version WHERE purpose='ACCESS_TOKEN'");
            }
        }
    }

    private void exercise(Connection admin) throws Exception {
        var service = service();
        UUID subject = UUID.randomUUID();
        assertEquals(0, operator("rotate").waitFor());
        String original = service.issue(subject);
        assertEquals(subject, service().verify(original).subject());
        var first = service.jwks().get("keys").getFirst();
        assertEquals(Set.of("kid","kty","crv","x"), first.keySet());
        assertEquals("OKP", first.get("kty"));
        assertEquals("Ed25519", first.get("crv"));
        assertEquals(32, Base64.getUrlDecoder().decode(first.get("x")).length);
        String firstId = first.get("kid");
        String unknown = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"kid\":\"" + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class,
                () -> service.verify(original.substring(0, original.lastIndexOf('.') + 1) + unknown));

        var a = operator("rotate", "--expected", firstId);
        var b = operator("rotate", "--expected", firstId);
        assertEquals(1, List.of(a.waitFor(), b.waitFor()).stream().filter(code -> code == 0).count());
        var visible = service.jwks().get("keys");
        assertEquals(2, visible.size());
        String newId = visible.getLast().get("kid");
        assertNotEquals(firstId, newId);
        assertEquals(subject, service.verify(original).subject());
        String current = service.issue(subject);
        assertEquals(subject, service().verify(current).subject());

        try (var expire = admin.prepareStatement("""
                UPDATE tt.signing_key_version SET verify_until=clock_timestamp()-interval '1 second'
                WHERE key_id=? AND status='VERIFY_ONLY'
                """)) {
            expire.setObject(1, UUID.fromString(firstId));
            assertEquals(1, expire.executeUpdate());
        }
        assertThrows(IllegalArgumentException.class, () -> service.verify(original));
        assertEquals(1, service.jwks().get("keys").size());
        assertEquals(0, operator("revoke", newId).waitFor());
        assertThrows(IllegalArgumentException.class, () -> service.verify(current));
        assertTrue(service.jwks().get("keys").isEmpty());
        assertThrows(IllegalStateException.class, () -> service.issue(subject));
    }
}
