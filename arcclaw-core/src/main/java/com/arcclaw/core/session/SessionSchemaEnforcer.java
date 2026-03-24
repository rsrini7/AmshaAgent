package com.arcclaw.core.session;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SessionSchemaEnforcer {

    private static final Logger log = Logger.getLogger(SessionSchemaEnforcer.class);

    @Inject
    EntityManager em;

    @Transactional
    void onStart(@Observes @Priority(Interceptor.Priority.APPLICATION + 90) StartupEvent ev) {
        em.createNativeQuery("""
            CREATE TABLE IF NOT EXISTS session_registry (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                channel_type TEXT NOT NULL,
                channel_id   TEXT NOT NULL,
                user_id      TEXT NOT NULL,
                session_id   TEXT NOT NULL UNIQUE,
                created_at   TEXT NOT NULL,
                UNIQUE(channel_type, channel_id, user_id)
            )
        """).executeUpdate();
        log.info("SessionSchemaEnforcer: session_registry table verified.");
    }
}

