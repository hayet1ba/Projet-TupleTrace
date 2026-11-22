package tn.supcom.tupletrace.api;

import jakarta.ejb.EJBAccessException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class EjbAccessDeniedMapper implements ExceptionMapper<EJBAccessException> {
    @Override
    public Response toResponse(EJBAccessException denied) {
        return Response.status(Response.Status.FORBIDDEN).header("Cache-Control", "no-store").build();
    }
}
