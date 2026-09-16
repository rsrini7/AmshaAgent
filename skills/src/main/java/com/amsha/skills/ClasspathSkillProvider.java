package com.amsha.skills;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.amsha.common.util.LogUtil;
import com.amsha.core.skill.SkillDefinition;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

/**
 * Loads bundled skills from the static classpath index at {@code /skills/index.txt}.
 * <p>
 * SkillsJars skills are loaded by {@link SkillsJarSkillProvider} instead,
 * which has a distinct {@code sourceName()} of {@code "skillsjars"} to enable
 * third-party security checks (HITL approval for sensitive capabilities).
 */
@Unremovable
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
    public int priority() {
        return 100;
    }

    @Override
    public List<SkillDefinition> load() {
        List<SkillDefinition> skills = new ArrayList<>();
        try (InputStream indexStream = getResourceStream(INDEX_PATH)) {
            if (indexStream == null) {
                LOG.warnf("classpath %s missing. Skipping static tier.", INDEX_PATH);
                return skills;
            }

            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(indexStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;

                    String resourcePath = "/skills/" + line;
                    try (InputStream skillStream = getResourceStream(resourcePath)) {
                        if (skillStream == null) {
                            LOG.warnf("Skill file '%s' listed in index.txt but not found on classpath", resourcePath);
                            continue;
                        }
                        String content = new String(skillStream.readAllBytes(), StandardCharsets.UTF_8);
                        skills.add(parser.parse(content));
                    } catch (Exception e) {
                        LOG.warnf("Failed to load static skill %s: %s", resourcePath, LogUtil.rootMessage(e));
                    }
                }
            }
        } catch (Exception e) {
            LOG.errorf("Fatal failure reading static skill index %s: %s", INDEX_PATH, LogUtil.rootMessage(e));
        }
        return skills;
    }

    InputStream getResourceStream(String path) {
        return getClass().getResourceAsStream(path);
    }
}
