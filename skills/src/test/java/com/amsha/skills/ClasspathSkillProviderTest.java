package com.amsha.skills;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amsha.core.skill.SkillDefinition;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClasspathSkillProviderTest {

    @Mock
    SkillDefinitionParser parser;

    @InjectMocks
    ClasspathSkillProvider provider;

    @Test
    void testMetadata() {
        assertEquals("classpath", provider.sourceName());
        assertEquals(100, provider.priority());
    }

    @Test
    void testMissingIndexFileReturnsEmptyList() {
        ClasspathSkillProvider spyProvider = spy(provider);
        doReturn(null).when(spyProvider).getResourceStream(anyString());

        List<SkillDefinition> skills = spyProvider.load();
        assertNotNull(skills);
        assertTrue(skills.isEmpty());
    }

    @Test
    void testIndexWithValidAndMalformedEntries() {
        Map<String, byte[]> virtualResources = new HashMap<>();
        String indexContent = "# Static skills index\n\nvalid-skill.yaml\nmissing-skill.yaml\nerror-skill.yaml\n";
        virtualResources.put("/skills/index.txt", indexContent.getBytes(StandardCharsets.UTF_8));
        virtualResources.put("/skills/valid-skill.yaml", "name: valid\n".getBytes(StandardCharsets.UTF_8));
        virtualResources.put("/skills/error-skill.yaml", "name: error\n".getBytes(StandardCharsets.UTF_8));

        ClasspathSkillProvider spyProvider = spy(provider);
        doAnswer(inv -> {
                    String path = inv.getArgument(0);
                    byte[] data = virtualResources.get(path);
                    return data != null ? new ByteArrayInputStream(data) : null;
                })
                .when(spyProvider)
                .getResourceStream(anyString());

        SkillDefinition mockDef = mock(SkillDefinition.class);
        when(parser.parse("name: valid\n")).thenReturn(mockDef);
        when(parser.parse("name: error\n")).thenThrow(new RuntimeException("malformed yaml"));

        List<SkillDefinition> skills = spyProvider.load();
        assertEquals(1, skills.size());
        assertSame(mockDef, skills.get(0));
    }

    @Test
    void testIndexReadFailureHandledGracefully() throws Exception {
        ClasspathSkillProvider spyProvider = spy(provider);
        InputStream failingStream = mock(InputStream.class);
        when(failingStream.read(any(byte[].class), anyInt(), anyInt())).thenThrow(new IOException("I/O error"));
        doReturn(failingStream).when(spyProvider).getResourceStream("/skills/index.txt");

        List<SkillDefinition> skills = spyProvider.load();
        assertNotNull(skills);
        assertTrue(skills.isEmpty());
    }
}
