package com.arcclaw.core.session;

import org.jboss.logging.Logger;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class SessionSchemaEnforcer {

    private static final Logger log = Logger.getLogger(SessionSchemaEnforcer.class);

    @Inject
    EntityManager em;

    @Transactional
    void onStart(@Observes @Priority(90) StartupEvent ev) {
        em.createNativeQuery("""
            CREATE TABLE IF NOT EXISTS session_registry (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                channel_type T  EXT NOT NULL,
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

