package com.arcclaw.core.skill;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.domain.model.AgentSkill;
import org.jboss.logging.Logger;

@ApplicationScoped
public class DatabaseSkillProvider implements SkillProvider {

    private static final Logger LOG = Logger.getLogger(DatabaseSkillProvider.class);

    @Inject
    AgentSkillRepository repository;

    @Inject
    SkillDefinitionParser parser;

    @Override
    public String sourceName() {
        return "database";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public List<SkillDefinition> load() {
        List<SkillDefinition> skills = new ArrayList<>();
        try {
            List<AgentSkill> dbSkills = repository.findAllSkills();
            for (AgentSkill entity : dbSkills) {
                try {
                    skills.add(parser.parse(entity.getDefinitionYaml()));
                } catch (Exception e) {
                    LOG.warnf("Failed to load database skill '%s': %s", entity.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.errorf("Failed to query database skills: %s", e.getMessage());
        }
        return skills;
    }
}
