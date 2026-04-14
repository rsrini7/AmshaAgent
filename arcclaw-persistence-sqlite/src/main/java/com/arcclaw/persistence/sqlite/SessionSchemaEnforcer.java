package com.arcclaw.persistence.sqlite;

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
            CREATE TABLE IF NOT EXISTS sessionregistry (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                channeltype TEXT NOT NULL,
                channelid   TEXT NOT NULL,
                userid      TEXT NOT NULL,
                sessionid   TEXT NOT NULL UNIQUE,
                createdat   TEXT NOT NULL,
                UNIQUE(channeltype, channelid, userid)
            )
        """).executeUpdate();
        log.info("SessionSchemaEnforcer: sessionregistry table verified.");
    }
}

