package com.arcclaw.app;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;

@ApplicationScoped
public class DummyRoute extends RouteBuilder {
    @Override
    public void configure() {
        from("direct:dummy")
            .routeId("dummy-route")
            .log("Dummy route active for ServeCommandTest");
    }
}
