package com.arcclaw.deployment;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.Path;
import io.smallrye.common.annotation.RunOnVirtualThread;

@Path("/valid")
@RunOnVirtualThread
public class ValidResource {
    @Inject
    EntityManager em;
}
