package tn.supcom.tupletrace.api;

import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import tn.supcom.tupletrace.security.AccessTokens;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.sql.SQLException;

@Stateless
@DenyAll
@Path("/.well-known/jwks.json")
public class JwksResource {
    @Inject
    AccessTokens tokens;

    @GET
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public Response publicKeys() {
        try {
            return Response.ok(tokens.jwks()).header("Cache-Control", "no-store").build();
        } catch (IOException | SQLException | GeneralSecurityException unavailable) {
            return Response.status(503).header("Cache-Control", "no-store").build();
        }
    }
}
