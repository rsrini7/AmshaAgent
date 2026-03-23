package com.arcclaw.app.security;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;



@QuarkusTest
@TestProfile(DevSecretSeederStartupTest.DevProfile.class)
class DevSecretSeederStartupTest {

    public static class DevProfile implements QuarkusTestProfile {
        @Override
        public String getConfigProfile() {
            return "dev";
        }
    }

    @Test
    void devSecretSeederStartupClassExistsAndIsAnnotatedCorrectly() throws Exception {
        Class<?> clazz = Class.forName("com.arcclaw.app.security.DevSecretSeederStartup");
        org.junit.jupiter.api.Assertions.assertTrue(
            clazz.isAnnotationPresent(io.quarkus.arc.profile.IfBuildProfile.class),
            "DevSecretSeederStartup must be annotated @IfBuildProfile");
        io.quarkus.arc.profile.IfBuildProfile ann = clazz.getAnnotation(io.quarkus.arc.profile.IfBuildProfile.class);
        org.junit.jupiter.api.Assertions.assertEquals("dev", ann.value(), "Must be excluded from non-dev builds");
    }
}
