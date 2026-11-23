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
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;

@Path("/auth")
@Stateless
@DenyAll
@TransactionAttribute(TransactionAttributeType.REQUIRED)
public class SessionResource {
    @Resource(lookup = "java:/jdbc/TupleTraceDS") DataSource database;

    @POST
    @Path("/logout")
    @RolesAllowed("AUTHENTICATED_ACCOUNT")
    public Response logout(@Context HttpServletRequest request) {
        var identity = (AccessTokens.Identity) request.getAttribute(AccessTokens.Identity.class.getName());
        if (identity == null) return status(401);
        try (var connection = database.getConnection();
             var revoke = connection.prepareStatement("SELECT tt.logout_token(?,?)")) {
            revoke.setObject(1, identity.tokenId());
            revoke.setTimestamp(2, Timestamp.from(identity.expiresAt()));
            revoke.execute();
            return audited(connection, identity, "IAM_LOGOUT", identity.tokenId());
        } catch (SQLException failure) {
            throw new IamAudit.StorageFailure(failure);
        }
    }

    @POST
    @Path("/factor/revoke")
    @RolesAllowed("AUTHENTICATED_ACCOUNT")
    public Response revokeFactor(@Context HttpServletRequest request) {
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
                if (!result.getBoolean(1)) return status(403);
            }
            return audited(connection, identity, "IAM_FACTOR_REVOKED", identity.proof().factor());
        } catch (SQLException failure) {
            throw new IamAudit.StorageFailure(failure);
        }
    }

    private static Response audited(Connection connection, AccessTokens.Identity identity,
                                    String event, UUID target) throws SQLException {
        var correlation = IamAudit.append(connection, identity, event, target);
        return Response.noContent().header("Cache-Control", "no-store").header(IamAudit.CORRELATION, correlation).build();
    }

    private static Response status(int code) {
        return Response.status(code).header("Cache-Control", "no-store").build();
    }
}
