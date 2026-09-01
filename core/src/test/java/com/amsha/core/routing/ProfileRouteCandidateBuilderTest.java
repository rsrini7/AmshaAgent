package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.inject.spi.BeanManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.channel.api.AttachmentType;
import com.amsha.core.config.CoreSpiResolutionValidator;
import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.ChatModelCreationPort;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmProfileStorePort;
import com.amsha.persistence.api.ModelProfileDto;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class ProfileRouteCandidateBuilderTest {

    private ProfileRouteCandidateBuilder builder;
    private ModelProfileResolver profileResolver;
    private ChatModelPool modelPool;
    private ModelProfileCredentialResolver credentialResolver;
    private LlmProfileStorePort profileStore;
    private AgentLlmConfig agentConfig;
    private BeanManager beanManager;
    private ChatModelCreationPort creationPort;

    @BeforeEach
    void setUp() {
        profileResolver = mock(ModelProfileResolver.class);
        modelPool = mock(ChatModelPool.class);
        credentialResolver = mock(ModelProfileCredentialResolver.class);
        profileStore = mock(LlmProfileStorePort.class);
        agentConfig = mock(AgentLlmConfig.class);
        beanManager = mock(BeanManager.class);
        creationPort = mock(ChatModelCreationPort.class);

        builder = new ProfileRouteCandidateBuilder();
        builder.profileResolver = profileResolver;
        builder.modelPool = modelPool;
        builder.credentialResolver = credentialResolver;
        builder.profileStore = profileStore;
        builder.agentConfig = agentConfig;
        builder.beanManager = beanManager;
        builder.profileRoutingEnabled = true;
    }

    @Test
    void returnsEmptyWhenProfileRoutingDisabled() {
        builder.profileRoutingEnabled = false;

        List<ModelRoute> candidates = builder.buildCandidates("researcher");
        assertTrue(candidates.isEmpty());
    }

    @Test
    void buildsCandidatesSuccessfully() {
        try (MockedStatic<CoreSpiResolutionValidator> spi = Mockito.mockStatic(CoreSpiResolutionValidator.class)) {
            spi.when(() -> CoreSpiResolutionValidator.resolveOptional(beanManager, ChatModelCreationPort.class))
                    .thenReturn(Optional.of(creationPort));

            ResolvedProfile rp =
                    new ResolvedProfile("prof-1", "conn-1", "qwen-vl", "omlx", "USER", "user-1", 1, 1, true);
            RouteResolution res = RouteResolution.of("user-role", List.of(rp));
            when(profileResolver.resolve("researcher")).thenReturn(res);

            LlmConnectionDto conn = new LlmConnectionDto(
                    "conn-1",
                    "USER",
                    "user-1",
                    "user-1",
                    "test",
                    "omlx",
                    null,
                    null,
                    "none",
                    null,
                    "ACTIVE",
                    1,
                    Instant.now(),
                    Instant.now());
            when(profileStore.findConnection("conn-1")).thenReturn(Optional.of(conn));

            ResolvedCredential cred = mock(ResolvedCredential.class);
            when(cred.baseUrl()).thenReturn("http://localhost");
            when(cred.apiKey()).thenReturn("key");
            when(credentialResolver.resolve(eq(conn), anyString())).thenReturn(Optional.of(cred));

            String capJson = "{\"supportedTypes\":[\"IMAGE\"]}";
            ModelProfileDto profDto = new ModelProfileDto(
                    "prof-1",
                    "conn-1",
                    "qwen-vl",
                    "PROMPT_DIRECTIVE",
                    capJson,
                    null,
                    true,
                    1,
                    Instant.now(),
                    Instant.now());
            when(profileStore.findModelProfile("prof-1")).thenReturn(Optional.of(profDto));

            ChatModel model = mock(ChatModel.class);
            when(modelPool.getOrCreateTyped(
                            anyString(),
                            anyString(),
                            anyString(),
                            ArgumentMatchers.anyInt(),
                            anyString(),
                            ArgumentMatchers.anyInt(),
                            anyString(),
                            anyString(),
                            any()))
                    .thenReturn(model);

            List<ModelRoute> candidates = builder.buildCandidates("researcher");

            assertEquals(1, candidates.size());
            ModelRoute route = candidates.get(0);
            assertEquals("profile:prof-1", route.routeId());
            assertEquals("omlx", route.providerName());
            assertEquals("qwen-vl", route.modelName());
            assertTrue(route.capability().supportsType(AttachmentType.IMAGE));
            assertTrue(route.model() instanceof LazyChatModel);
            // Verify lazy initialization works
            route.model().provider();
            Mockito.verify(modelPool)
                    .getOrCreateTyped(
                            anyString(),
                            anyString(),
                            anyString(),
                            ArgumentMatchers.anyInt(),
                            anyString(),
                            ArgumentMatchers.anyInt(),
                            anyString(),
                            anyString(),
                            any());
        }
    }

    @Test
    void skipsWhenConnectionMissing() {
        ResolvedProfile rp = new ResolvedProfile("prof-1", "conn-1", "qwen-vl", "omlx", "USER", "user-1", 1, 1, true);
        RouteResolution res = RouteResolution.of("user-role", List.of(rp));
        when(profileResolver.resolve("researcher")).thenReturn(res);

        when(profileStore.findConnection("conn-1")).thenReturn(Optional.empty());

        List<ModelRoute> candidates = builder.buildCandidates("researcher");
        assertTrue(candidates.isEmpty());
    }

    @Test
    void skipsWhenCredentialMissing() {
        ResolvedProfile rp = new ResolvedProfile("prof-1", "conn-1", "qwen-vl", "omlx", "USER", "user-1", 1, 1, true);
        RouteResolution res = RouteResolution.of("user-role", List.of(rp));
        when(profileResolver.resolve("researcher")).thenReturn(res);

        LlmConnectionDto conn = new LlmConnectionDto(
                "conn-1",
                "USER",
                "user-1",
                "user-1",
                "test",
                "omlx",
                null,
                null,
                "none",
                null,
                "ACTIVE",
                1,
                Instant.now(),
                Instant.now());
        when(profileStore.findConnection("conn-1")).thenReturn(Optional.of(conn));

        when(credentialResolver.resolve(eq(conn), anyString())).thenReturn(Optional.empty());

        List<ModelRoute> candidates = builder.buildCandidates("researcher");
        assertTrue(candidates.isEmpty());
    }

    @Test
    void capabilityFallsBackToHeuristic() {
        try (MockedStatic<CoreSpiResolutionValidator> spi = Mockito.mockStatic(CoreSpiResolutionValidator.class)) {
            spi.when(() -> CoreSpiResolutionValidator.resolveOptional(beanManager, ChatModelCreationPort.class))
                    .thenReturn(Optional.of(creationPort));

            ResolvedProfile rp =
                    new ResolvedProfile("prof-1", "conn-1", "Qwen3.8-27B-4bit", "omlx", "USER", "user-1", 1, 1, true);
            RouteResolution res = RouteResolution.of("user-role", List.of(rp));
            when(profileResolver.resolve("researcher")).thenReturn(res);

            LlmConnectionDto conn = new LlmConnectionDto(
                    "conn-1",
                    "USER",
                    "user-1",
                    "user-1",
                    "test",
                    "omlx",
                    null,
                    null,
                    "none",
                    null,
                    "ACTIVE",
                    1,
                    Instant.now(),
                    Instant.now());
            when(profileStore.findConnection("conn-1")).thenReturn(Optional.of(conn));

            ResolvedCredential cred = mock(ResolvedCredential.class);
            when(credentialResolver.resolve(eq(conn), anyString())).thenReturn(Optional.of(cred));

            // No capabilityJson
            ModelProfileDto profDto = new ModelProfileDto(
                    "prof-1",
                    "conn-1",
                    "Qwen3.8-27B-4bit",
                    "PROMPT_DIRECTIVE",
                    null,
                    null,
                    true,
                    1,
                    Instant.now(),
                    Instant.now());
            when(profileStore.findModelProfile("prof-1")).thenReturn(Optional.of(profDto));

            ChatModel model = mock(ChatModel.class);
            when(modelPool.getOrCreateTyped(
                            anyString(),
                            anyString(),
                            anyString(),
                            ArgumentMatchers.anyInt(),
                            anyString(),
                            ArgumentMatchers.anyInt(),
                            anyString(),
                            anyString(),
                            any()))
                    .thenReturn(model);

            List<ModelRoute> candidates = builder.buildCandidates("researcher");

            assertEquals(1, candidates.size());
            ModelRoute route = candidates.get(0);
            // Qwen3.8 heuristic grants IMAGE
            assertTrue(route.capability().supportsType(AttachmentType.IMAGE));
        }
    }

    @Test
    void lazyInitializationFailsWhenProfileDisabledAtExecutionTime() {
        ResolvedProfile rp = new ResolvedProfile("prof-1", "conn-1", "qwen-vl", "omlx", "USER", "user-1", 1, 1, true);
        RouteResolution res = RouteResolution.of("user-role", List.of(rp));
        when(profileResolver.resolve("researcher")).thenReturn(res);

        LlmConnectionDto conn = new LlmConnectionDto(
                "conn-1",
                "USER",
                "user-1",
                "user-1",
                "test",
                "omlx",
                null,
                null,
                "none",
                null,
                "ACTIVE",
                1,
                Instant.now(),
                Instant.now());
        when(profileStore.findConnection("conn-1")).thenReturn(Optional.of(conn));

        ResolvedCredential cred = mock(ResolvedCredential.class);
        when(credentialResolver.resolve(eq(conn), anyString())).thenReturn(Optional.of(cred));

        ModelProfileDto profDto = new ModelProfileDto(
                "prof-1", "conn-1", "qwen-vl", "PROMPT_DIRECTIVE", null, null, true, 1, Instant.now(), Instant.now());
        when(profileStore.findModelProfile("prof-1")).thenReturn(Optional.of(profDto));

        List<ModelRoute> candidates = builder.buildCandidates("researcher");
        assertEquals(1, candidates.size());
        ModelRoute route = candidates.get(0);

        // Later at execution time, profile is disabled in store
        ModelProfileDto disabledDto = new ModelProfileDto(
                "prof-1", "conn-1", "qwen-vl", "PROMPT_DIRECTIVE", null, null, false, 1, Instant.now(), Instant.now());
        when(profileStore.findModelProfile("prof-1")).thenReturn(Optional.of(disabledDto));

        Assertions.assertThrows(IllegalStateException.class, () -> route.model().provider());
    }

    @Test
    void lazyInitializationFailsWhenRevisionMismatchedAtExecutionTime() {
        ResolvedProfile rp = new ResolvedProfile("prof-1", "conn-1", "qwen-vl", "omlx", "USER", "user-1", 1, 1, true);
        RouteResolution res = RouteResolution.of("user-role", List.of(rp));
        when(profileResolver.resolve("researcher")).thenReturn(res);

        LlmConnectionDto conn = new LlmConnectionDto(
                "conn-1",
                "USER",
                "user-1",
                "user-1",
                "test",
                "omlx",
                null,
                null,
                "none",
                null,
                "ACTIVE",
                1,
                Instant.now(),
                Instant.now());
        when(profileStore.findConnection("conn-1")).thenReturn(Optional.of(conn));

        ResolvedCredential cred = mock(ResolvedCredential.class);
        when(credentialResolver.resolve(eq(conn), anyString())).thenReturn(Optional.of(cred));

        ModelProfileDto profDto = new ModelProfileDto(
                "prof-1", "conn-1", "qwen-vl", "PROMPT_DIRECTIVE", null, null, true, 1, Instant.now(), Instant.now());
        when(profileStore.findModelProfile("prof-1")).thenReturn(Optional.of(profDto));

        List<ModelRoute> candidates = builder.buildCandidates("researcher");
        assertEquals(1, candidates.size());
        ModelRoute route = candidates.get(0);

        // Later at execution time, profile revision in store was updated to 2
        ModelProfileDto updatedDto = new ModelProfileDto(
                "prof-1", "conn-1", "qwen-vl", "PROMPT_DIRECTIVE", null, null, true, 2, Instant.now(), Instant.now());
        when(profileStore.findModelProfile("prof-1")).thenReturn(Optional.of(updatedDto));

        Assertions.assertThrows(IllegalStateException.class, () -> route.model().provider());
    }
}
