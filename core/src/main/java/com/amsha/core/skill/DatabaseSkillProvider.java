package com.amsha.core.skill;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

import com.amsha.common.util.LogUtil;
import com.amsha.persistence.api.SkillDto;
import com.amsha.persistence.api.SkillStorePort;

import org.jboss.logging.Logger;

@ApplicationScoped
public class DatabaseSkillProvider implements SkillProvider {

    private static final Logger LOG = Logger.getLogger(DatabaseSkillProvider.class);

    @Inject
    SkillStorePort skillStore;

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
            List<SkillDto> dbSkills = skillStore.findAll();
            for (SkillDto dto : dbSkills) {
                try {
                    skills.add(parser.parse(dto.definitionYaml()));
                } catch (Exception e) {
                    LOG.warnf("Failed to load database skill '%s': %s", dto.name(), LogUtil.rootMessage(e));
                }
            }
        } catch (Exception e) {
            LOG.errorf("Failed to query database skills: %s", LogUtil.rootMessage(e));
        }
        return skills;
    }
}
