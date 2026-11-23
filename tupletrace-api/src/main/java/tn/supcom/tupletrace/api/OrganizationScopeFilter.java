package tn.supcom.tupletrace.api;

import jakarta.annotation.Priority;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

@Provider
@OrganizationBound
@Priority(Priorities.AUTHORIZATION)
public class OrganizationScopeFilter implements ContainerRequestFilter {
    @Context HttpServletRequest request;

    @Override
    public void filter(ContainerRequestContext context) {
        var member = (AccountDirectory.Membership) request.getAttribute(AccountDirectory.Membership.class.getName());
        String organization = context.getUriInfo().getPathParameters().getFirst("organization");
        if (member == null || !member.organization().toString().equalsIgnoreCase(organization)) {
            context.abortWith(Response.status(member == null ? 401 : 404).header("Cache-Control", "no-store").build());
        }
    }
}
