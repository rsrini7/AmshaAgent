package com.amsha.deployment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import org.jboss.logging.Logger;

import io.quarkus.deployment.ApplicationArchive;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;

public class SkillsJarBuildStep {

    private static final Logger LOG = Logger.getLogger(SkillsJarBuildStep.class);
    private static final String SKILLS_DIR = "META-INF/skills";
    private static final String INDEX_PATH = SKILLS_DIR + "/skills-index.txt";

    @BuildStep
    void discoverAndRegisterSkills(
            ApplicationArchivesBuildItem applicationArchives,
            BuildProducer<NativeImageResourceBuildItem> nativeResources,
            BuildProducer<GeneratedResourceBuildItem> generatedResources) {

        List<String> skillPaths = new ArrayList<>();

        for (ApplicationArchive archive : applicationArchives.getAllApplicationArchives()) {
            for (Path rootDir : archive.getRootDirectories()) {
                Path skillsDir = rootDir.resolve(SKILLS_DIR);
                if (!Files.exists(skillsDir) || !Files.isDirectory(skillsDir)) {
                    continue;
                }
                try {
                    Files.walkFileTree(skillsDir, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            String relativePath =
                                    rootDir.relativize(file).toString().replace("\\", "/");
                            if (relativePath.endsWith("/SKILL.md")) {
                                synchronized (skillPaths) {
                                    skillPaths.add(relativePath);
                                }
                                LOG.debugf("Discovered SkillsJar skill: %s", relativePath);
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } catch (IOException e) {
                    LOG.warnf("Failed to scan %s for SkillsJars: %s", skillsDir, e.getMessage());
                }
            }
        }

        if (!skillPaths.isEmpty()) {
            LOG.infof("Discovered %d SkillsJars skill(s) at build-time", skillPaths.size());
            String indexContent = String.join("\n", skillPaths);
            generatedResources.produce(
                    new GeneratedResourceBuildItem(INDEX_PATH, indexContent.getBytes(StandardCharsets.UTF_8)));
            nativeResources.produce(new NativeImageResourceBuildItem(INDEX_PATH));

            for (String path : skillPaths) {
                nativeResources.produce(new NativeImageResourceBuildItem(path));
            }
        } else {
            LOG.debug("No SkillsJars skills discovered at build-time");
        }
    }
}
