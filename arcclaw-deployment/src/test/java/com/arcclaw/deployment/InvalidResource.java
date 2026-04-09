package com.arcclaw.deployment;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.Path;

@Path("/invalid")
public class InvalidResource {
    @Inject
    EntityManager em;
}
