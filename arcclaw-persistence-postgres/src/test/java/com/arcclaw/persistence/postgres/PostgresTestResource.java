package com.arcclaw.persistence.postgres;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.github.dockerjava.api.DockerClient;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

@SuppressWarnings("resource")
public class PostgresTestResource implements QuarkusTestResourceLifecycleManager {

    private PostgreSQLContainer<?> postgres;
    private EmbeddedPostgres embeddedDb;

    @Override
    public Map<String, String> start() {
        Map<String, String> props = new HashMap<>();

        if (isDockerAvailable()) {
            postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("test")
                    .withUsername("test")
                    .withPassword("test")
                    .waitingFor(Wait.forListeningPort());

            postgres.start();

            props.put("quarkus.datasource.jdbc.url", postgres.getJdbcUrl());
            props.put("quarkus.datasource.username", postgres.getUsername());
            props.put("quarkus.datasource.password", postgres.getPassword());
        } else {
            try {
                embeddedDb = EmbeddedPostgres.builder().start();
                props.put("quarkus.datasource.jdbc.url", embeddedDb.getJdbcUrl("postgres", "postgres"));
                props.put("quarkus.datasource.username", "postgres");
                props.put("quarkus.datasource.password", "postgres");
            } catch (IOException e) {
                throw new RuntimeException("Failed to start embedded PostgreSQL database", e);
            }
        }
        return props;
    }

    @Override
    public void stop() {
        if (postgres != null) {
            postgres.stop();
        }
        if (embeddedDb != null) {
            try {
                embeddedDb.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    private boolean isDockerAvailable() {
        try (DockerClient _ = DockerClientFactory.instance().client()) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
