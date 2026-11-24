package tn.supcom.tupletrace.api;

import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ejb.Stateless;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.UUID;

@Stateless
@DenyAll
@Path("/organizations")
@OrganizationBound
public class OrganizationResource {
    @GET
    @Path("/{organization}")
    @RolesAllowed("AUDITEUR")
    @Produces(MediaType.APPLICATION_JSON)
    public Response organization(@PathParam("organization") UUID organization, @Context HttpServletRequest request) {
        var membership = (AccountDirectory.Membership) request.getAttribute(AccountDirectory.Membership.class.getName());
        if (membership == null || !membership.organization().equals(organization)) return Response.status(404).build();
        return Response.ok(Map.of("id", organization, "name", membership.organizationName())).build();
    }
}
