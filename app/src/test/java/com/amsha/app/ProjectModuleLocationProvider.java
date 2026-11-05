package com.amsha.app;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.LocationProvider;

/** Provides compiled reactor module outputs to ArchUnit without scanning the JVM classpath. */
public final class ProjectModuleLocationProvider implements LocationProvider {

    @Override
    public Set<Location> get(Class<?> testClass) {
        try {
            Path testOutput = Path.of(testClass
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            Path reactorRoot = testOutput.getParent().getParent().getParent();
            try (Stream<Path> entries = Files.list(reactorRoot)) {
                Set<Location> locations = entries.filter(Files::isDirectory)
                        .filter(module -> Files.isRegularFile(module.resolve("pom.xml")))
                        .map(module -> module.resolve("target/classes"))
                        .filter(Files::isDirectory)
                        .map(Location::of)
                        .collect(Collectors.toUnmodifiableSet());
                if (locations.isEmpty()) {
                    throw new IllegalStateException("No compiled reactor module outputs found for architecture checks");
                }
                return locations;
            }
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException("Unable to locate reactor module outputs for architecture checks", e);
        }
    }
}
