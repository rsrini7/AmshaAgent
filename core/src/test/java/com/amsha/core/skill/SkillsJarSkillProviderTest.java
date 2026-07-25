package com.amsha.core.skill;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SkillsJarSkillProviderTest {

    @Mock
    SkillDefinitionParser parser;

    @Mock
    SkillAdaptationManager adaptationManager;

    @InjectMocks
    SkillsJarSkillProvider provider;

    @Test
    void testMetadata() {
        assertEquals("skillsjars", provider.sourceName());
        assertEquals(50, provider.priority());
    }

    @Test
    void testLoadHandlesEmptyOrIndex() {
        List<SkillDefinition> loaded = provider.load();
        assertNotNull(loaded);
    }
}
