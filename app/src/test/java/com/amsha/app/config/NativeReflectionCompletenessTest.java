package com.amsha.app.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import com.amsha.app.ui.dto.AgentEvent;
import com.amsha.app.ui.dto.ChatMessageDto;
import com.amsha.app.ui.dto.PageDto;
import com.amsha.persistence.api.ExternalIdentityDto;
import com.amsha.persistence.api.IdentityInvitationDto;
import com.amsha.persistence.api.IdentityUserDto;
import com.amsha.persistence.api.WorkspaceMembershipDto;

import org.junit.jupiter.api.Test;

import io.quarkus.runtime.annotations.RegisterForReflection;

class NativeReflectionCompletenessTest {

    @Test
    void instantiateReflectionConfigClasses() {
        assertNotNull(new AppReflectionConfig());
        assertNotNull(new ChannelReflectionConfig());
    }

    @Test
    void appReflectionConfigTargetsAreValid() {
        RegisterForReflection annotation = AppReflectionConfig.class.getAnnotation(RegisterForReflection.class);
        assertNotNull(annotation, "AppReflectionConfig must have @RegisterForReflection");

        Class<?>[] targets = annotation.targets();
        assertTrue(targets.length > 20, "AppReflectionConfig must declare comprehensive reflection targets");

        Set<Class<?>> targetSet = Set.of(targets);

        // Core UI / API DTOs
        assertTrue(targetSet.contains(AgentEvent.class));
        assertTrue(targetSet.contains(ChatMessageDto.class));
        assertTrue(targetSet.contains(PageDto.class));

        // Persistence DTOs
        assertTrue(targetSet.contains(IdentityUserDto.class));
        assertTrue(targetSet.contains(WorkspaceMembershipDto.class));
        assertTrue(targetSet.contains(ExternalIdentityDto.class));
        assertTrue(targetSet.contains(IdentityInvitationDto.class));
    }

    @Test
    void appReflectionConfigClassNamesExistOnClasspath() {
        RegisterForReflection annotation = AppReflectionConfig.class.getAnnotation(RegisterForReflection.class);
        assertNotNull(annotation);

        String[] classNames = annotation.classNames();
        assertTrue(classNames.length > 0, "AppReflectionConfig must declare classNames for inaccessible/nested types");

        for (String className : classNames) {
            assertDoesNotThrow(
                    () -> Class.forName(className),
                    () -> "Class declared in AppReflectionConfig classNames must exist on classpath: " + className);
        }
    }

    @Test
    void channelReflectionConfigTargetsAreValid() {
        RegisterForReflection annotation = ChannelReflectionConfig.class.getAnnotation(RegisterForReflection.class);
        assertNotNull(annotation, "ChannelReflectionConfig must have @RegisterForReflection");

        Class<?>[] targets = annotation.targets();
        assertTrue(targets.length > 5, "ChannelReflectionConfig must declare targets");

        for (Class<?> target : targets) {
            assertNotNull(target, "Target class in ChannelReflectionConfig must not be null");
        }
    }
}
