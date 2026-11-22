package tn.supcom.tupletrace.probe;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import tn.supcom.tupletrace.security.AccessTokens;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;

/** Déploiement de test éphémère. Aucun point d'entrée HTTP. */
@Singleton
@Startup
public class RestartProbe {
    @Resource(lookup = "java:/jdbc/TupleTraceDS")
    private DataSource database;

    @PostConstruct
    public void test() {
        var tokens = new AccessTokens(database, Path.of(System.getenv("TT_KEY_DIR")),
                () -> System.getenv("TT_KEYSTORE_PASSWORD").toCharArray(),
                System.getenv("TT_TOKEN_ISSUER"), System.getenv("TT_TOKEN_AUDIENCE"), Clock.systemUTC());
        Path directory = Path.of(System.getenv("TT_PROBE_DIR"));
        Path previous = directory.resolve("access-token");
        try {
            if (Files.exists(previous)) {
                var identity = tokens.verify(Files.readString(previous));
                tokens.verify(tokens.issue(identity.subject()));
                Files.createFile(directory.resolve("verified"));
            } else {
                String token = tokens.issue(UUID.randomUUID());
                tokens.verify(token);
                Files.writeString(previous, token, java.nio.file.StandardOpenOption.CREATE_NEW);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Access-token restart check failed", failure);
        }
    }
}
