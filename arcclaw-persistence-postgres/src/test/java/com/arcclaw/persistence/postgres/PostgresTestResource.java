package com.arcclaw.persistence.postgres;

import java.util.HashMap;
import java.util.Map;

import com.github.dockerjava.api.DockerClient;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;

@SuppressWarnings("resource")
public class PostgresTestResource implements QuarkusTestResourceLifecycleManager {

    private PostgreSQLContainer<?> postgres;

    @Override
    public Map<String, String> start() {
        if (!isDockerAvailable()) {
            throw new RuntimeException("Docker is not available - Postgres integration tests cannot run");
        }

        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test")
            .waitingFor(Wait.forListeningPort());

        postgres.start();

        Map<String, String> props = new HashMap<>();
        props.put("quarkus.datasource.jdbc.url", postgres.getJdbcUrl());
        props.put("quarkus.datasource.username", postgres.getUsername());
        props.put("quarkus.datasource.password", postgres.getPassword());
        return props;
    }

    @Override
    public void stop() {
        if (postgres != null) {
            postgres.stop();
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
