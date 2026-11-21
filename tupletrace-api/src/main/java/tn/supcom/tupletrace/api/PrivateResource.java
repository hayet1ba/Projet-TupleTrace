package tn.supcom.tupletrace.api;

import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ejb.Stateless;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Stateless
@DenyAll
@Path("/private")
public class PrivateResource {
    @GET
    @RolesAllowed("AUDITEUR")
    @Produces(MediaType.TEXT_PLAIN)
    public String restrictedMessage() {
        return "TupleTrace";
    }
}
