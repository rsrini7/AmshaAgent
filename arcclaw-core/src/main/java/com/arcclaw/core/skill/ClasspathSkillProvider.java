package com.arcclaw.core.skill;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ClasspathSkillProvider implements SkillProvider {

    private static final Logger LOG = Logger.getLogger(ClasspathSkillProvider.class);
    private static final String INDEX_PATH = "/skills/index.txt";

    @Inject
    SkillDefinitionParser parser;

    @Override
    public String sourceName() {
        return "classpath";
    }

    @Override
    public List<SkillDefinition> load() {
        List<SkillDefinition> skills = new ArrayList<>();
        try (InputStream indexStream = getClass().getResourceAsStream(INDEX_PATH)) {
            if (indexStream == null) {
                LOG.warnf("classpath %s missing. Skipping static tier.", INDEX_PATH);
                return skills;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(indexStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;

                    String resourcePath = "/skills/" + line;
                    try (InputStream skillStream = getClass().getResourceAsStream(resourcePath)) {
                        if (skillStream == null) {
                            LOG.warnf("Skill file '%s' listed in index.txt but not found on classpath", resourcePath);
                            continue;
                        }
                        String content = new String(skillStream.readAllBytes(), StandardCharsets.UTF_8);
                        skills.add(parser.parse(content));
                    } catch (Exception e) {
                        LOG.warnf("Failed to load static skill %s: %s", resourcePath, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            LOG.errorf("Fatal failure reading static skill index %s: %s", INDEX_PATH, e.getMessage());
        }
        return skills;
    }
}
