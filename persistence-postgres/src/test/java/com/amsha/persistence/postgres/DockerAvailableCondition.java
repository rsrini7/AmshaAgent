package com.amsha.persistence.postgres;

import org.testcontainers.DockerClientFactory;

public final class DockerAvailableCondition {

    private DockerAvailableCondition() {}

    public static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
