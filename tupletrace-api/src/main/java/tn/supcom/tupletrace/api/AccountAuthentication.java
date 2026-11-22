package tn.supcom.tupletrace.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.security.auth.message.callback.PasswordValidationCallback;
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanism;
import jakarta.security.enterprise.authentication.mechanism.http.HttpMessageContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tn.supcom.tupletrace.security.AccessTokens;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.UnsupportedCallbackException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Base64;

@ApplicationScoped
public class AccountAuthentication implements HttpAuthenticationMechanism {
    @Inject AccessTokens tokens;
    @Inject AccountDirectory directory;

    @Override
    public AuthenticationStatus validateRequest(HttpServletRequest request, HttpServletResponse response,
                                                HttpMessageContext context) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.equals("/api/auth/login")) response.setHeader("Cache-Control", "no-store");
        var headers = request.getHeaders("Authorization");
        if (!headers.hasMoreElements()) {
            return context.isProtected() ? unauthorized(response) : context.doNothing();
        }
        String header = headers.nextElement();
        if (!request.isSecure() || headers.hasMoreElements() || header.length() > 8200) return unauthorized(response);
        try {
            if (header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                var token = tokens.verify(header.substring(7));
                if (token.organization() == null) return unauthorized(response);
                var member = directory.active(token.subject());
                if (member == null || !member.organization().equals(token.organization())) return unauthorized(response);
                request.setAttribute(AccountDirectory.Membership.class.getName(), member);
                // Elytron charge les rôles du principal dans le realm JDBC pour le servlet et les EJB.
                return context.notifyContainerAboutLogin(member.principal(), null);
            }
            if (path.equals("/api/private") && header.regionMatches(true, 0, "Basic ", 0, 6)) {
                return legacyBasic(header.substring(6), response, context);
            }
            return unauthorized(response);
        } catch (IllegalArgumentException invalid) {
            return unauthorized(response);
        } catch (IOException | GeneralSecurityException | SQLException | UnsupportedCallbackException unavailable) {
            response.setStatus(503);
            response.setHeader("Cache-Control", "no-store");
            return AuthenticationStatus.SEND_FAILURE;
        }
    }

    private static AuthenticationStatus legacyBasic(String encoded, HttpServletResponse response,
                                                    HttpMessageContext context) throws IOException, UnsupportedCallbackException {
        byte[] pair = Base64.getDecoder().decode(encoded);
        try {
            int separator = 0;
            while (separator < pair.length && pair[separator] != ':') separator++;
            if (separator == 0 || separator == pair.length) return unauthorized(response);
            String name = new String(pair, 0, separator, StandardCharsets.UTF_8);
            char[] password = new String(pair, separator + 1, pair.length - separator - 1, StandardCharsets.UTF_8).toCharArray();
            var callback = new PasswordValidationCallback(context.getClientSubject(), name, password);
            try {
                context.getHandler().handle(new Callback[]{callback});
                return callback.getResult() ? context.notifyContainerAboutLogin(name, null) : unauthorized(response);
            } finally {
                callback.clearPassword();
                Arrays.fill(password, '\0');
            }
        } finally {
            Arrays.fill(pair, (byte) 0);
        }
    }

    private static AuthenticationStatus unauthorized(HttpServletResponse response) {
        response.setStatus(401);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("WWW-Authenticate", "Bearer realm=\"TupleTrace\"");
        return AuthenticationStatus.SEND_FAILURE;
    }
}
