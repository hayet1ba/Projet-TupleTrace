package tn.supcom.tupletrace.api;

import jakarta.annotation.Resource;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.stream.JsonParser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import tn.supcom.tupletrace.security.AccessTokens;
import tn.supcom.tupletrace.security.PasswordAccounts;

import javax.sql.DataSource;
import java.io.*;
import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.util.*;

@Path("/auth/login")
@Stateless
@DenyAll
@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class LoginResource {
    @Inject AccessTokens tokens;
    @Inject AccountDirectory directory;
    @Resource(lookup = "java:/jdbc/TupleTraceDS") DataSource database;

    @POST
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response login(InputStream body, @Context HttpServletRequest request) throws IOException {
        byte[] bytes = body.readNBytes(8193);
        char[] password = null;
        try {
            if (bytes.length > 8192) return response(413).build();
            Map<String, String> fields = credentials(bytes);
            if (fields == null) return response(400).build();
            password = fields.remove("password").toCharArray();
            var check = new PasswordAccounts(database).verify(fields.get("email"), password,
                    InetAddress.getByName(request.getRemoteAddr()), UUID.randomUUID());
            if (check.status() == PasswordAccounts.Status.RATE_LIMITED) return response(429).build();
            if (check.status() != PasswordAccounts.Status.VERIFIED) return response(401).build();
            var membership = directory.active(check.userId());
            if (membership == null) return response(401).build();
            String token = tokens.issue(membership.user(), membership.organization());
            return response(200).entity(Map.of("access_token", token, "token_type", "Bearer")).build();
        } catch (SQLException | IOException | GeneralSecurityException | IllegalStateException unavailable) {
            return response(503).build();
        } finally {
            Arrays.fill(bytes, (byte) 0);
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    private static Response.ResponseBuilder response(int status) {
        return Response.status(status).header("Cache-Control", "no-store");
    }

    private static Map<String, String> credentials(byte[] bytes) {
        var fields = new HashMap<String, String>();
        try (var parser = Json.createParser(new ByteArrayInputStream(bytes))) {
            if (parser.next() != JsonParser.Event.START_OBJECT) return null;
            for (var event = parser.next(); event != JsonParser.Event.END_OBJECT; event = parser.next()) {
                if (event != JsonParser.Event.KEY_NAME) return null;
                String name = parser.getString();
                if (!(name.equals("email") || name.equals("password")) || parser.next() != JsonParser.Event.VALUE_STRING
                        || fields.putIfAbsent(name, parser.getString()) != null) return null;
            }
            return fields.size() == 2 && !parser.hasNext() ? fields : null;
        } catch (JsonException | NoSuchElementException invalid) {
            return null;
        }
    }
}
