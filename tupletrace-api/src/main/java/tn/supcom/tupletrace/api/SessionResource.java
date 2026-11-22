package tn.supcom.tupletrace.api;

import jakarta.annotation.Resource;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import tn.supcom.tupletrace.security.AccessTokens;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;

@Path("/auth")
@Stateless
@DenyAll
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class SessionResource {
    @Resource(lookup = "java:/jdbc/TupleTraceDS") DataSource database;

    @POST
    @Path("/logout")
    @RolesAllowed("AUTHENTICATED_ACCOUNT")
    public Response logout(@Context HttpServletRequest request) throws SQLException {
        var identity = (AccessTokens.Identity) request.getAttribute(AccessTokens.Identity.class.getName());
        if (identity == null) return status(401);
        try (var connection = database.getConnection();
             var revoke = connection.prepareStatement("SELECT tt.logout_token(?,?)")) {
            revoke.setObject(1, identity.tokenId());
            revoke.setTimestamp(2, Timestamp.from(identity.expiresAt()));
            revoke.execute();
        }
        return status(204);
    }

    @POST
    @Path("/factor/revoke")
    @RolesAllowed("AUTHENTICATED_ACCOUNT")
    public Response revokeFactor(@Context HttpServletRequest request) throws SQLException {
        var identity = (AccessTokens.Identity) request.getAttribute(AccessTokens.Identity.class.getName());
        if (identity == null) return status(401);
        if (identity.proof() == null) return status(403);
        try (var connection = database.getConnection();
             var revoke = connection.prepareStatement("SELECT tt.remove_totp(?,?,?)")) {
            revoke.setObject(1, identity.subject());
            revoke.setObject(2, identity.proof().factor());
            revoke.setTimestamp(3, Timestamp.from(identity.proof().time()));
            try (var result = revoke.executeQuery()) {
                result.next();
                return status(result.getBoolean(1) ? 204 : 403);
            }
        }
    }

    private static Response status(int code) {
        return Response.status(code).header("Cache-Control", "no-store").build();
    }
}
