package tn.supcom.tupletrace.api;

import jakarta.ws.rs.NameBinding;
import java.lang.annotation.*;

@NameBinding
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface OrganizationBound {}
