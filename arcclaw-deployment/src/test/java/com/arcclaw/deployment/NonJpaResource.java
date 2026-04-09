package com.arcclaw.deployment;

import jakarta.ws.rs.Path;

@Path("/non-jpa")
public class NonJpaResource {
    // No JPA fields, should be ignored by build step
}
