package tn.supcom.tupletrace.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanism;
import jakarta.security.enterprise.authentication.mechanism.http.HttpMessageContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tn.supcom.tupletrace.security.AccessTokens;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.sql.SQLException;

@ApplicationScoped
public class AccountAuthentication implements HttpAuthenticationMechanism {
    @Inject AccessTokens tokens;
    @Inject AccountDirectory directory;

    @Override
    public AuthenticationStatus validateRequest(HttpServletRequest request, HttpServletResponse response,
                                                HttpMessageContext context) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/auth/")) response.setHeader("Cache-Control", "no-store");
        var headers = request.getHeaders("Authorization");
        if (!headers.hasMoreElements()) {
            return context.isProtected() ? unauthorized(response) : context.doNothing();
        }
        String header = headers.nextElement();
        if (!request.isSecure() || headers.hasMoreElements() || header.length() > 8200) return unauthorized(response);
        try {
            if (header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                AccessTokens.Identity token;
                try {
                    token = tokens.verify(header.substring(7));
                } catch (IllegalArgumentException invalidToken) {
                    return unauthorized(response);
                }
                if (token.organization() == null) return unauthorized(response);
                var member = directory.active(token.subject());
                if (member == null || !member.organization().equals(token.organization())) return unauthorized(response);
                var proof = token.proof();
                if (proof == null && (member.privileged() || member.factor() != null)) return unauthorized(response);
                if (proof != null && (!member.confirmed() || !proof.factor().equals(member.factor()))) return unauthorized(response);
                request.setAttribute(AccessTokens.Identity.class.getName(), token);
                request.setAttribute(AccountDirectory.Membership.class.getName(), member);
                return context.notifyContainerAboutLogin(member.principal(), member.groups());
            }
            return unauthorized(response);
        } catch (IOException | GeneralSecurityException | SQLException unavailable) {
            response.setStatus(503);
            response.setHeader("Cache-Control", "no-store");
            return AuthenticationStatus.SEND_FAILURE;
        }
    }

    private static AuthenticationStatus unauthorized(HttpServletResponse response) {
        response.setStatus(401);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("WWW-Authenticate", "Bearer realm=\"TupleTrace\"");
        return AuthenticationStatus.SEND_FAILURE;
    }
}
