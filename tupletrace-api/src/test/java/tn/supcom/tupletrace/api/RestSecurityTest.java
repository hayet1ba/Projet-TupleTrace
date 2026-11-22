package tn.supcom.tupletrace.api;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RestSecurityTest {
    @Test
    void healthReportsLiveness() {
        assertEquals(Map.of("status", "UP"), new HealthResource().health());
    }

    @Test
    void productionResourcesDenyByDefaultAndDeclareAccessForEachHttpMethod() {
        var classes = new ClassFileImporter().importPath(java.nio.file.Path.of("target", "classes"));
        assertTrue(classes.contain(HealthResource.class));
        assertTrue(classes.contain(PrivateResource.class));
        verifyAccess(classes);
    }

    private static void verifyAccess(JavaClasses classes) {
        for (var type : classes) {
            if (type.isAnnotatedWith(Path.class)) {
                assertTrue(type.isAnnotatedWith(DenyAll.class), type.getName());
            }
            for (var method : type.getMethods()) {
                if (method.isMetaAnnotatedWith(HttpMethod.class)) {
                    verifyMethod(method.reflect());
                }
            }
        }
    }

    private static void verifyMethod(Method method) {
        var roles = method.getDeclaredAnnotation(RolesAllowed.class);
        boolean permitAll = method.isAnnotationPresent(PermitAll.class);
        boolean denyAll = method.isAnnotationPresent(DenyAll.class);
        int policies = (roles == null ? 0 : 1) + (permitAll ? 1 : 0) + (denyAll ? 1 : 0);
        assertEquals(1, policies, method.toString());
        if (roles != null) {
            assertTrue(roles.value().length > 0 && Arrays.stream(roles.value()).noneMatch(String::isBlank),
                    method.toString());
        }
        if (permitAll) {
            var publicRoutes = Map.of(HealthResource.class, "/health#health",
                    JwksResource.class, "/.well-known/jwks.json#publicKeys",
                    LoginResource.class, "/auth/login#login");
            var resource = method.getDeclaringClass();
            assertTrue(publicRoutes.containsKey(resource), method.toString());
            assertEquals(publicRoutes.get(resource), resource.getAnnotation(Path.class).value() + "#" + method.getName());
            assertTrue(method.isAnnotationPresent(resource == LoginResource.class ? POST.class : GET.class));
            assertFalse(method.isAnnotationPresent(Path.class));
        }
    }

    @Test
    void rejectsAnUnannotatedHttpMethodEvenOnADenyAllResource() {
        var witness = new ClassFileImporter().importClasses(MissingPolicy.class);
        assertThrows(AssertionError.class, () -> verifyAccess(witness));
    }

    @Test
    void rejectsAPublicAnnotationOnAPrivateResource() {
        var witness = new ClassFileImporter().importClasses(AccidentallyPublic.class);
        assertThrows(AssertionError.class, () -> verifyAccess(witness));
    }

    @DenyAll
    @Path("/private")
    static class MissingPolicy {
        @GET
        public String get() { return ""; }
    }

    @DenyAll
    @Path("/private")
    static class AccidentallyPublic {
        @GET
        @PermitAll
        public String get() { return ""; }
    }
}
