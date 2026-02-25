package com.arcclaw.app.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;

@ApplicationScoped
public class ClockProducer {

    @Produces
    @ApplicationScoped
    public Clock clock() {
        return Clock.systemUTC();
    }
}
