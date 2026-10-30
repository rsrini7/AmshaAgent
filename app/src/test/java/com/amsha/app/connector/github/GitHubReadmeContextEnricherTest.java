package com.amsha.app.connector.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.WorkspaceMembershipDto;

import org.junit.jupiter.api.Test;

class GitHubReadmeContextEnricherTest {

    @Test
    void resolvesWorkspaceFromIdentityStoreWhenActiveWorkspaceIsUnbound() {
        GitHubReadmeConnectorService connectorService = mock(GitHubReadmeConnectorService.class);
        IdentityStorePort identityStore = mock(IdentityStorePort.class);

        String userId = "usr_de9755abf8a24867";
        String workspaceId = "ws_4bda07b78eff44b6";
        String goal = "Explain the Channel SPI architecture in this repository.";
        Instant now = Instant.now();

        when(identityStore.listMembershipsByUserId(userId))
                .thenReturn(List.of(new WorkspaceMembershipDto(workspaceId, userId, "OWNER", "ACTIVE", 1, now, now)));
        when(connectorService.matchingMarkdown(userId, workspaceId, goal))
                .thenReturn(Optional.of("Source: docs/CHANNEL_SPI.md (commit 0fc0ae1)\n# Channel SPI Documentation"));

        GitHubReadmeContextEnricher enricher = new GitHubReadmeContextEnricher(connectorService, identityStore);

        String result = enricher.enrich("session-1", userId, goal, "Base system prompt");

        assertTrue(result.contains("## Connected GitHub documentation context"));
        assertTrue(result.contains("docs/CHANNEL_SPI.md"));
        assertTrue(result.contains("# Channel SPI Documentation"));
    }

    @Test
    void doesNotChooseAnArbitraryWorkspaceWhenMembershipIsAmbiguous() {
        GitHubReadmeConnectorService connectorService = mock(GitHubReadmeConnectorService.class);
        IdentityStorePort identityStore = mock(IdentityStorePort.class);
        String userId = "usr_de9755abf8a24867";
        Instant now = Instant.now();
        when(identityStore.listMembershipsByUserId(userId))
                .thenReturn(List.of(
                        new WorkspaceMembershipDto("ws_first", userId, "OWNER", "ACTIVE", 1, now, now),
                        new WorkspaceMembershipDto("ws_second", userId, "MEMBER", "ACTIVE", 1, now, now)));
        GitHubReadmeContextEnricher enricher = new GitHubReadmeContextEnricher(connectorService, identityStore);

        String result = enricher.enrich("session-1", userId, "Explain repository architecture.", "Base system prompt");

        assertEquals("Base system prompt", result);
        verifyNoInteractions(connectorService);
        verify(identityStore, never()).listWorkspaces();
    }

    @Test
    void doesNotChooseAnUnrelatedWorkspaceWhenTheUserHasNoMembership() {
        GitHubReadmeConnectorService connectorService = mock(GitHubReadmeConnectorService.class);
        IdentityStorePort identityStore = mock(IdentityStorePort.class);
        String userId = "usr_de9755abf8a24867";
        when(identityStore.listMembershipsByUserId(userId)).thenReturn(List.of());
        GitHubReadmeContextEnricher enricher = new GitHubReadmeContextEnricher(connectorService, identityStore);

        String result = enricher.enrich("session-1", userId, "Explain repository architecture.", "Base system prompt");

        assertEquals("Base system prompt", result);
        verifyNoInteractions(connectorService);
        verify(identityStore, never()).listWorkspaces();
    }

    @Test
    void returnsBasePromptWhenGoalOrUserIsBlank() {
        GitHubReadmeConnectorService connectorService = mock(GitHubReadmeConnectorService.class);
        IdentityStorePort identityStore = mock(IdentityStorePort.class);

        GitHubReadmeContextEnricher enricher = new GitHubReadmeContextEnricher(connectorService, identityStore);

        assertEquals("Base prompt", enricher.enrich("session-1", null, "goal", "Base prompt"));
        assertEquals("Base prompt", enricher.enrich("session-1", "user-1", "", "Base prompt"));
    }
}
