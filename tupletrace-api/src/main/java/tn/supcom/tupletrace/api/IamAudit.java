package tn.supcom.tupletrace.api;

import jakarta.annotation.Resource;
import jakarta.ejb.ApplicationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import tn.supcom.tupletrace.security.AccessTokens;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

@Provider
public class IamAudit implements ContainerResponseFilter {
    static final String CORRELATION = "X-Correlation-ID";
    @Resource(lookup = "java:/jdbc/TupleTraceDS") DataSource database;
    @Context HttpServletRequest request;

    @Override
    public void filter(ContainerRequestContext context, ContainerResponseContext response) {
        var identity = (AccessTokens.Identity) request.getAttribute(AccessTokens.Identity.class.getName());
        if (identity == null) return;
        String event;
        if (response.getStatus() == 403) event = "IAM_AUTHORIZATION_DENIED";
        else if (response.getStatus() == 404 && context.getUriInfo().getPathParameters().containsKey("organization"))
            event = "IAM_ORGANIZATION_NOT_FOUND";
        else return;
        try (var connection = database.getConnection()) {
            UUID correlation = append(connection, identity, event, null);
            response.getHeaders().putSingle(CORRELATION, correlation.toString());
        } catch (SQLException failure) {
            throw new StorageFailure(failure);
        }
    }

    static UUID append(Connection connection, AccessTokens.Identity identity, String event, UUID target)
            throws SQLException {
        UUID correlation = UUID.randomUUID();
        try (var statement = connection.prepareStatement("SELECT tt.append_audit_event(?,?,?,?,?)")) {
            statement.setObject(1, identity.organization());
            statement.setObject(2, identity.subject());
            statement.setString(3, event);
            statement.setObject(4, correlation);
            statement.setObject(5, target);
            statement.execute();
        }
        return correlation;
    }

    @ApplicationException(rollback = true)
    static class StorageFailure extends InternalServerErrorException {
        StorageFailure(SQLException failure) {
            super(Response.serverError().header("Cache-Control", "no-store").build(), failure);
            System.getLogger(IamAudit.class.getName()).log(System.Logger.Level.ERROR,
                    "IAM database operation failed (SQLSTATE {0})", failure.getSQLState());
        }
    }
}
