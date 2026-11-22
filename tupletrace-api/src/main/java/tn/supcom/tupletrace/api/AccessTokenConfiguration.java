package tn.supcom.tupletrace.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import tn.supcom.tupletrace.security.AccessTokens;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Clock;

@ApplicationScoped
public class AccessTokenConfiguration {
    @Produces
    public AccessTokens accessTokens() throws NamingException {
        DataSource ds = InitialContext.doLookup("java:/jdbc/TupleTraceDS");
        return new AccessTokens(ds, Path.of(required("TT_KEY_DIR")),
                () -> required("TT_KEYSTORE_PASSWORD").toCharArray(),
                required("TT_TOKEN_ISSUER"), required("TT_TOKEN_AUDIENCE"), Clock.systemUTC());
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }
}
