package com.amsha.skills;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import com.amsha.core.skill.SkillDefinition;
import com.amsha.persistence.api.SkillDto;
import com.amsha.persistence.api.SkillStorePort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DatabaseSkillProviderTest {

    @Mock
    SkillStorePort skillStore;

    @Mock
    SkillDefinitionParser parser;

    @InjectMocks
    DatabaseSkillProvider provider;

    @Test
    void testMetadata() {
        assertEquals("database", provider.sourceName());
        assertEquals(10, provider.priority());
    }

    @Test
    void testLoadSuccessAndPartialFailure() {
        SkillDto validDto = mock(SkillDto.class);
        when(validDto.definitionYaml()).thenReturn("valid yaml");

        SkillDto errorDto = mock(SkillDto.class);
        when(errorDto.name()).thenReturn("error-skill");
        when(errorDto.definitionYaml()).thenReturn("broken yaml");

        when(skillStore.findAll()).thenReturn(List.of(validDto, errorDto));

        SkillDefinition validDef = mock(SkillDefinition.class);
        when(parser.parse("valid yaml")).thenReturn(validDef);
        when(parser.parse("broken yaml")).thenThrow(new RuntimeException("YAML parse failure"));

        List<SkillDefinition> loaded = provider.load();
        assertEquals(1, loaded.size());
        assertSame(validDef, loaded.get(0));
    }

    @Test
    void testLoadStoreThrowsException() {
        when(skillStore.findAll()).thenThrow(new RuntimeException("DB down"));

        List<SkillDefinition> loaded = provider.load();
        assertNotNull(loaded);
        assertTrue(loaded.isEmpty());
    }
}
