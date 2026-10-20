package com.amsha.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import com.amsha.channel.api.preference.UserPreferenceService;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.onboarding.dto.NetworkProfilePolicyDto;
import com.amsha.onboarding.dto.NetworkProfileUpdateRequest;
import com.amsha.onboarding.dto.PromptUpdateRequest;
import com.amsha.onboarding.dto.ProviderUpdateRequest;
import com.amsha.onboarding.dto.SettingsDto;
import com.amsha.onboarding.dto.VoiceSettingsUpdateRequest;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmProfileStorePort;
import com.amsha.persistence.api.OnboardingDraftDto;
import com.amsha.persistence.api.OnboardingDraftPurpose;
import com.amsha.persistence.api.OnboardingDraftState;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;
import com.amsha.security.api.VaultKey;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Container-free unit tests for {@link SettingsResource}.
 *
 * <p>The sibling {@code SettingsResourceTest} is a {@code @QuarkusTest} that exercises the happy paths over HTTP. Over
 * HTTP the refusal arms are largely unreachable: the security filter always installs a principal (so the
 * {@code X-Amsha-API-Key} fallback in {@link SettingsResource#requireOwner()} never runs), RestAssured always sends a
 * body (so the "request body is required" arms never run), and the durable coordinator always commits (so the
 * {@code 409} arms never run). This test drives the resource directly with mocked ports, which makes every
 * {@code 401} / {@code 400} / {@code 409} arm reachable, plus the two vault-fallback arms
 * ({@code readVaultString}'s unreadable-key catch and the prompt-truncation ternary).
 *
 * <p>Every collaborator is a package-private {@code @Inject} field, so it is assigned directly — no CDI container and no
 * reflection.
 */
class SettingsResourceUnitTest {

    private static final String OWNER = "usr_settings_unit";
    private static final String INTERNAL_KEY = "key-internal-unit-test";
    private static final String LONG_PROMPT = "p".repeat(250);

    /** Wizard-staged connection: carries no id, so it has no committed profile-store row. */
    private static final String CONNECTION_PAYLOAD =
            "{\"connections\":[{\"provider\":\"openai\",\"modelId\":\"gpt-4o-mini\"}],\"concernRoutes\":{\"agentLoop\":[0]}}";

    /** Imported connection: carries the explicit id used to correlate the committed row. */
    private static final String CONNECTION_PAYLOAD_WITH_ID =
            "{\"connections\":[{\"id\":\"conn_1\",\"provider\":\"openai\",\"modelId\":\"gpt-4o-mini\"}]}";

    /** Shape accepted by {@code NetworkProfilePolicy.isValidJson} and {@code NetworkProfilePolicyDto.validate}. */
    private static final String VALID_PROFILE_JSON =
            "{\"egressMode\":\"ALLOW_ALL\",\"tlsTrustMode\":\"SYSTEM\",\"requireProxy\":false}";

    private SettingsResource resource;
    private SecretVault vault;
    private OnboardingCoordinator coordinator;
    private LlmConfigurationProvenance provenance;
    private AuditService auditService;
    private UserPreferenceService userPreferenceService;
    private Instance<LlmProfileStorePort> profileStoreInstance;
    private LlmProfileStorePort profileStore;
    private SecurityContext securityContext;
    private HttpHeaders httpHeaders;

    @BeforeEach
    void setUp() {
        resource = new SettingsResource();
        vault = mock(SecretVault.class);
        coordinator = mock(OnboardingCoordinator.class);
        provenance = mock(LlmConfigurationProvenance.class);
        auditService = mock(AuditService.class);
        userPreferenceService = mock(UserPreferenceService.class);
        profileStoreInstance = mockInstance();
        profileStore = mock(LlmProfileStorePort.class);
        securityContext = mock(SecurityContext.class);
        httpHeaders = mock(HttpHeaders.class);

        resource.vault = vault;
        resource.coordinator = coordinator;
        resource.configurationProvenance = provenance;
        resource.auditService = auditService;
        resource.userPreferenceService = userPreferenceService;
        resource.profileStoreInstance = profileStoreInstance;
        resource.objectMapper = new ObjectMapper();
        resource.appVersion = "1.2.3-test";
        resource.internalApiKey = INTERNAL_KEY;
        resource.skipOnboarding = false;
        resource.securityContext = securityContext;
        resource.httpHeaders = httpHeaders;

        when(profileStoreInstance.isResolvable()).thenReturn(true);
        when(profileStoreInstance.get()).thenReturn(profileStore);
        when(provenance.resolve()).thenReturn(resolution(false));
        // A fresh VaultKey per call: the resource consumes it inside try-with-resources and zeroes it.
        when(vault.retrieveSystemKey(anyString()))
                .thenAnswer(invocation -> Optional.of(new VaultKey("seed".getBytes(StandardCharsets.UTF_8))));
    }

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> mockInstance() {
        return mock(Instance.class);
    }

    private static LlmConfigurationProvenance.Resolution resolution(boolean ready) {
        return new LlmConfigurationProvenance.Resolution(
                "openai", "gpt-4o-mini", "https://api.openai.com/v1", "CONFIG", "PRESENT", "VAULT", true, ready);
    }

    private static OnboardingDraftDto draft(String draftId, OnboardingDraftState state, List<String> selections) {
        Instant now = Instant.now();
        return new OnboardingDraftDto(
                draftId,
                OWNER,
                OnboardingDraftPurpose.FIRST_RUN,
                "plan-fingerprint",
                "connections",
                List.of(),
                selections,
                null,
                null,
                state,
                1,
                now.plusSeconds(3600),
                now,
                now);
    }

    private static LlmConnectionDto connection(String id, String owner, String networkProfileJson) {
        Instant now = Instant.now();
        return new LlmConnectionDto(
                id,
                "USER",
                owner,
                owner,
                "Display",
                "openai",
                null,
                null,
                "BEARER",
                networkProfileJson,
                "ACTIVE",
                1,
                now,
                now);
    }

    private void asOwner() {
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn(OWNER);
        when(securityContext.getUserPrincipal()).thenReturn(principal);
    }

    private void asAnonymous() {
        when(securityContext.getUserPrincipal()).thenReturn(null);
        when(httpHeaders.getHeaderString("X-Amsha-API-Key")).thenReturn(null);
    }

    private void asInternalKey() {
        when(securityContext.getUserPrincipal()).thenReturn(null);
        when(httpHeaders.getHeaderString("X-Amsha-API-Key")).thenReturn(INTERNAL_KEY);
    }

    private static void assertUnauthorized(Supplier<Response> call) {
        assertEquals(401, call.get().getStatus());
    }

    // ---- requireOwner ----

    @Test
    @DisplayName("every owner-gated endpoint refuses an anonymous caller")
    void allEndpointsRejectAnonymous() {
        asAnonymous();

        assertUnauthorized(() -> resource.updateVoice(null, null));
        assertUnauthorized(() -> resource.getPrompt(null));
        assertUnauthorized(() -> resource.updateProvider(null, new ProviderUpdateRequest("openai", "m", "k")));
        assertUnauthorized(() -> resource.getConnectionNetworkProfile(null, 0));
        assertUnauthorized(() -> resource.updateConnectionNetworkProfile(
                null, new NetworkProfileUpdateRequest(0, VALID_PROFILE_JSON, null)));
        assertUnauthorized(() -> resource.updatePrompt(null, new PromptUpdateRequest("p")));
        assertUnauthorized(() -> resource.reset(null));

        // getSettings reports the refusal as an exception rather than a Response.
        WebApplicationException refusal = assertThrows(WebApplicationException.class, () -> resource.getSettings(null));
        assertEquals(401, refusal.getResponse().getStatus());
    }

    @Test
    @DisplayName("the internal API key header authorises a principal-less caller as owner \"internal\"")
    void internalApiKeyHeaderAuthorisesWithoutPrincipal() {
        asInternalKey();

        Response response = resource.updatePrompt(null, new PromptUpdateRequest("hello"));

        assertEquals(200, response.getStatus());
        verify(vault).storeSystemKey(eq(VaultConstants.SYSTEM_PROMPT_KEY), any(byte[].class));
        // The audit record carries the synthesised owner, proving requireOwner() took the fallback arm.
        verify(auditService)
                .recordMandatory(
                        eq("settings"),
                        eq("internal"),
                        eq("system"),
                        eq(SecurityEventType.SETTINGS_CHANGED),
                        anyString());
    }

    @Test
    @DisplayName("a non-matching API key header does not authorise")
    void mismatchedApiKeyHeaderDoesNotAuthorise() {
        when(securityContext.getUserPrincipal()).thenReturn(null);
        when(httpHeaders.getHeaderString("X-Amsha-API-Key")).thenReturn("wrong-key");

        assertUnauthorized(() -> resource.getPrompt(null));
    }

    // ---- getSettings / readVaultString ----

    @Test
    void getSettingsTruncatesAnOverlongSystemPrompt() {
        asOwner();
        when(vault.retrieveSystemKey(VaultConstants.SYSTEM_PROMPT_KEY))
                .thenAnswer(invocation -> Optional.of(new VaultKey(LONG_PROMPT.getBytes(StandardCharsets.UTF_8))));
        when(vault.systemKeyExists(VaultConstants.LLM_API_KEY)).thenReturn(true);
        when(userPreferenceService.getVoice(OWNER)).thenReturn(Optional.of("vivian"));
        when(userPreferenceService.getOutputMode(OWNER)).thenReturn(Optional.of("TEXT_AND_AUDIO"));
        when(userPreferenceService.getAvailableVoices()).thenReturn(List.of("default", "vivian"));

        SettingsDto dto = resource.getSettings(null);

        assertEquals("openai", dto.provider());
        assertEquals("gpt-4o-mini", dto.model());
        assertEquals("1.2.3-test", dto.appVersion());
        assertTrue(dto.hasApiKey());
        assertTrue(dto.vaultPriority());
        assertEquals(200, dto.systemPrompt().length());
        assertTrue(dto.systemPrompt().endsWith("…"));
        assertEquals("vivian", dto.voice());
        assertEquals("TEXT_AND_AUDIO", dto.speechOutputMode());
        assertEquals(List.of("default", "vivian"), dto.availableVoices());
    }

    @Test
    @DisplayName("skipOnboarding short-circuits backendLlmConfigured without a second provenance lookup")
    void skipOnboardingShortCircuitsProvenance() {
        asOwner();
        resource.skipOnboarding = true;
        when(provenance.resolve()).thenReturn(resolution(false));

        SettingsDto dto = resource.getSettings(null);

        assertTrue(dto.skipOnboarding());
        // Exactly one resolve(): the one on the response path. backendLlmConfigured() short-circuited on the flag.
        verify(provenance, times(1)).resolve();
    }

    @Test
    @DisplayName("an unreadable vault key degrades to the fallback instead of failing the read")
    void getPromptFallsBackWhenTheVaultKeyIsUnreadable() {
        asOwner();
        when(vault.retrieveSystemKey(VaultConstants.SYSTEM_PROMPT_KEY)).thenAnswer(invocation -> {
            VaultKey destroyed = new VaultKey("gone".getBytes(StandardCharsets.UTF_8));
            destroyed.close();
            return Optional.of(destroyed);
        });

        Response response = resource.getPrompt(null);

        assertEquals(200, response.getStatus());
        assertEquals(Map.of("systemPrompt", ""), response.getEntity());
    }

    @Test
    void getPromptReturnsTheStoredSystemPrompt() {
        asOwner();
        when(vault.retrieveSystemKey(VaultConstants.SYSTEM_PROMPT_KEY))
                .thenAnswer(invocation -> Optional.of(new VaultKey("be brief".getBytes(StandardCharsets.UTF_8))));

        assertEquals(
                Map.of("systemPrompt", "be brief"), resource.getPrompt(null).getEntity());
    }

    // ---- updateVoice ----

    @Test
    void updateVoiceMapsAnIllegalSelectionTo400() {
        asOwner();
        doThrow(new IllegalArgumentException("unknown voice"))
                .when(userPreferenceService)
                .updatePreferences(OWNER, "nope", "TEXT_ONLY");

        assertEquals(
                400,
                resource.updateVoice(null, new VoiceSettingsUpdateRequest("nope", "TEXT_ONLY"))
                        .getStatus());
    }

    @Test
    void updateVoiceDefaultsTheEchoWhenNoEffectivePreferenceExists() {
        asOwner();
        when(userPreferenceService.getEffectiveVoice(OWNER)).thenReturn(Optional.empty());
        when(userPreferenceService.getEffectiveOutputMode(OWNER)).thenReturn(Optional.empty());

        assertEquals(
                Map.of("voice", "default", "speechOutputMode", "TEXT_ONLY"),
                resource.updateVoice(null, new VoiceSettingsUpdateRequest(null, null))
                        .getEntity());
    }

    // ---- updateProvider ----

    @Test
    void updateProviderRejectsAMissingProviderWith400() {
        asOwner();

        assertEquals(
                400,
                resource.updateProvider(null, new ProviderUpdateRequest("   ", null, null))
                        .getStatus());
        assertEquals(
                400,
                resource.updateProvider(null, new ProviderUpdateRequest(null, null, null))
                        .getStatus());
        verify(coordinator, never()).getOrCreateFirstRunDraft(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateProviderFallsBackToTheProviderDefaultModel() {
        asOwner();
        OnboardingDraftDto staging = draft("draft_commit", OnboardingDraftState.DRAFT, List.of());
        when(coordinator.getOrCreateFirstRunDraft(OWNER)).thenReturn(staging);
        when(coordinator.updateStep(eq("draft_commit"), eq("connections"), anyList()))
                .thenReturn(Optional.of(staging));
        when(coordinator.commitDraft("draft_commit"))
                .thenReturn(Optional.of(draft("draft_commit", OnboardingDraftState.COMPLETED, List.of())));

        Response response = resource.updateProvider(null, new ProviderUpdateRequest("openai", "  ", "sk-live"));

        assertEquals(200, response.getStatus());
        assertEquals(Map.of("committed", true, "provider", "openai"), response.getEntity());
        ArgumentCaptor<List<String>> staged = ArgumentCaptor.forClass(List.class);
        verify(coordinator).updateStep(eq("draft_commit"), eq("connections"), staged.capture());
        assertTrue(staged.getValue().get(0).contains(ModelDefaults.DEFAULT_OPENAI_MODEL));
        verify(vault).storeSystemKey(eq(VaultConstants.LLM_API_KEY), any(byte[].class));
        verify(coordinator).moveToStep("draft_commit", "review");
        verify(auditService)
                .recordMandatory(
                        eq("settings"),
                        eq(OWNER),
                        eq("system"),
                        eq(SecurityEventType.SETTINGS_CHANGED),
                        contains("committed provider=openai"));
    }

    @Test
    void updateProviderReturns400WhenStagingTheSelectionFails() {
        asOwner();
        when(coordinator.getOrCreateFirstRunDraft(OWNER))
                .thenReturn(draft("draft_x", OnboardingDraftState.DRAFT, List.of()));
        when(coordinator.updateStep(anyString(), anyString(), anyList())).thenReturn(Optional.empty());

        assertEquals(
                400,
                resource.updateProvider(null, new ProviderUpdateRequest("openai", "gpt-4o", null))
                        .getStatus());
        verify(coordinator, never()).commitDraft(anyString());
    }

    @Test
    void updateProviderReturns409WhenTheCommitDoesNotComplete() {
        asOwner();
        OnboardingDraftDto staging = draft("draft_y", OnboardingDraftState.DRAFT, List.of());
        when(coordinator.getOrCreateFirstRunDraft(OWNER)).thenReturn(staging);
        when(coordinator.updateStep(anyString(), anyString(), anyList())).thenReturn(Optional.of(staging));
        // The saga returns the draft still in DRAFT: the commit did not reach COMPLETED.
        when(coordinator.commitDraft("draft_y")).thenReturn(Optional.of(staging));

        assertEquals(
                409,
                resource.updateProvider(null, new ProviderUpdateRequest("openai", "gpt-4o", null))
                        .getStatus());
        verify(auditService, never())
                .recordMandatory(anyString(), anyString(), anyString(), any(SecurityEventType.class), anyString());
    }

    // ---- getConnectionNetworkProfile ----

    @Test
    void getConnectionNetworkProfileFallsBackToTheCommittedStoreRow() {
        asOwner();
        when(coordinator.findActive(OWNER)).thenReturn(Optional.empty());
        when(profileStore.listConnectionsByCreatedBy(OWNER))
                .thenReturn(List.of(connection("conn_1", OWNER, VALID_PROFILE_JSON)));

        Response response = resource.getConnectionNetworkProfile(null, 0);

        assertEquals(200, response.getStatus());
        NetworkProfilePolicyDto dto = (NetworkProfilePolicyDto) response.getEntity();
        assertEquals("ALLOW_ALL", dto.egressMode());
        assertEquals("SYSTEM", dto.tlsTrustMode());
    }

    @Test
    void getConnectionNetworkProfileDefaultsWhenTheCommittedRowHasNoProfile() {
        asOwner();
        when(coordinator.findActive(OWNER)).thenReturn(Optional.empty());
        when(profileStore.listConnectionsByCreatedBy(OWNER)).thenReturn(List.of(connection("conn_1", OWNER, "  ")));

        Response response = resource.getConnectionNetworkProfile(null, 0);

        assertEquals(200, response.getStatus());
        assertEquals("ALLOW_ALL", ((NetworkProfilePolicyDto) response.getEntity()).egressMode());
    }

    @Test
    void getConnectionNetworkProfileDefaultsWhenTheStoreIsAbsent() {
        asOwner();
        when(coordinator.findActive(OWNER)).thenReturn(Optional.empty());
        when(profileStoreInstance.isResolvable()).thenReturn(false);

        Response response = resource.getConnectionNetworkProfile(null, 0);

        assertEquals("ALLOW_ALL", ((NetworkProfilePolicyDto) response.getEntity()).egressMode());
    }

    // ---- updateConnectionNetworkProfile ----

    @Test
    void updateConnectionNetworkProfileRejectsAMissingBodyWith400() {
        asOwner();

        assertEquals(400, resource.updateConnectionNetworkProfile(null, null).getStatus());
        verify(auditService, never())
                .recordMandatory(anyString(), anyString(), anyString(), any(SecurityEventType.class), anyString());
    }

    @Test
    void updateConnectionNetworkProfileAcceptsRawJsonAndStagesIt() {
        asOwner();
        OnboardingDraftDto active = draft("draft_raw", OnboardingDraftState.DRAFT, List.of(CONNECTION_PAYLOAD));
        when(coordinator.findActive(OWNER)).thenReturn(Optional.of(active));
        when(coordinator.updateConnectionNetworkProfile(eq("draft_raw"), eq(0), anyString()))
                .thenReturn(Optional.of(active));
        // No committed store: the mirror is skipped and the draft write stands alone.
        when(profileStoreInstance.isResolvable()).thenReturn(false);

        Response response = resource.updateConnectionNetworkProfile(
                null, new NetworkProfileUpdateRequest(0, VALID_PROFILE_JSON, null));

        assertEquals(200, response.getStatus());
        assertEquals(Map.of("updated", true, "draftId", "draft_raw"), response.getEntity());
        ArgumentCaptor<String> stagedJson = ArgumentCaptor.forClass(String.class);
        verify(coordinator).updateConnectionNetworkProfile(eq("draft_raw"), eq(0), stagedJson.capture());
        assertTrue(stagedJson.getValue().contains("ALLOW_ALL"));
        // Audit-before-mutation: the record lands before the draft transition.
        verify(auditService)
                .recordMandatory(
                        eq("settings"),
                        eq(OWNER),
                        eq("system"),
                        eq(SecurityEventType.SETTINGS_CHANGED),
                        contains("connectionIndex=0"));
    }

    @Test
    void updateConnectionNetworkProfileRejectsRawJsonThatFailsValidation() {
        asOwner();

        // Parseable, but the timeout exceeds MAX_TIMEOUT_MS.
        Response response = resource.updateConnectionNetworkProfile(
                null,
                new NetworkProfileUpdateRequest(0, "{\"egressMode\":\"ALLOW_ALL\",\"connectTimeoutMs\":400000}", null));

        assertEquals(400, response.getStatus());
        verify(coordinator, never()).findActive(anyString());
    }

    @Test
    void updateConnectionNetworkProfileRejectsAnEmptyPayloadWith400() {
        asOwner();

        assertEquals(
                400,
                resource.updateConnectionNetworkProfile(null, new NetworkProfileUpdateRequest(0, null, null))
                        .getStatus());
        assertEquals(
                400,
                resource.updateConnectionNetworkProfile(null, new NetworkProfileUpdateRequest(0, "   ", null))
                        .getStatus());
    }

    @Test
    void updateConnectionNetworkProfileCreatesADraftWhenNoneIsActive() {
        asOwner();
        OnboardingDraftDto created = draft("draft_new", OnboardingDraftState.DRAFT, List.of(CONNECTION_PAYLOAD));
        when(coordinator.findActive(OWNER)).thenReturn(Optional.empty());
        when(coordinator.getOrCreateFirstRunDraft(OWNER)).thenReturn(created);
        when(coordinator.updateConnectionNetworkProfile(eq("draft_new"), eq(0), anyString()))
                .thenReturn(Optional.of(created));
        when(profileStoreInstance.isResolvable()).thenReturn(false);

        Response response = resource.updateConnectionNetworkProfile(
                null, new NetworkProfileUpdateRequest(0, VALID_PROFILE_JSON, null));

        assertEquals(200, response.getStatus());
        verify(coordinator).getOrCreateFirstRunDraft(OWNER);
    }

    @Test
    void updateConnectionNetworkProfileReturns400WhenNoStagedSelectionExists() {
        asOwner();
        OnboardingDraftDto active = draft("draft_bare", OnboardingDraftState.DRAFT, List.of());
        when(coordinator.findActive(OWNER)).thenReturn(Optional.of(active));
        when(coordinator.updateConnectionNetworkProfile(eq("draft_bare"), eq(0), anyString()))
                .thenReturn(Optional.empty());
        when(profileStoreInstance.isResolvable()).thenReturn(false);

        assertEquals(
                400,
                resource.updateConnectionNetworkProfile(
                                null, new NetworkProfileUpdateRequest(0, VALID_PROFILE_JSON, null))
                        .getStatus());
    }

    @Test
    void updateConnectionNetworkProfileReturns409OnACommittedRevisionConflict() {
        asOwner();
        OnboardingDraftDto active =
                draft("draft_conflict", OnboardingDraftState.DRAFT, List.of(CONNECTION_PAYLOAD_WITH_ID));
        when(coordinator.findActive(OWNER)).thenReturn(Optional.of(active));
        when(coordinator.updateConnectionNetworkProfile(eq("draft_conflict"), eq(0), anyString()))
                .thenReturn(Optional.of(active));
        when(profileStore.findConnection("conn_1")).thenReturn(Optional.of(connection("conn_1", OWNER, "")));
        // The row lost a revision race: the optimistic-locking update reports false.
        when(profileStore.updateConnection(any(LlmConnectionDto.class), eq(1))).thenReturn(false);

        Response response = resource.updateConnectionNetworkProfile(
                null, new NetworkProfileUpdateRequest(0, VALID_PROFILE_JSON, null));

        assertEquals(409, response.getStatus());
    }

    @Test
    void updateConnectionNetworkProfileSkipsTheMirrorWhenTheStagedIdHasNoOwnedRow() {
        asOwner();
        OnboardingDraftDto active =
                draft("draft_orphan", OnboardingDraftState.DRAFT, List.of(CONNECTION_PAYLOAD_WITH_ID));
        when(coordinator.findActive(OWNER)).thenReturn(Optional.of(active));
        when(coordinator.updateConnectionNetworkProfile(eq("draft_orphan"), eq(0), anyString()))
                .thenReturn(Optional.of(active));
        when(profileStore.findConnection("conn_1")).thenReturn(Optional.empty());

        Response response = resource.updateConnectionNetworkProfile(
                null, new NetworkProfileUpdateRequest(0, VALID_PROFILE_JSON, null));

        assertEquals(200, response.getStatus());
        verify(profileStore, never()).updateConnection(any(LlmConnectionDto.class), anyInt());
    }

    @Test
    void updateConnectionNetworkProfileSkipsTheMirrorWhenTheStagedIdBelongsToAnotherOwner() {
        asOwner();
        OnboardingDraftDto active =
                draft("draft_foreign", OnboardingDraftState.DRAFT, List.of(CONNECTION_PAYLOAD_WITH_ID));
        when(coordinator.findActive(OWNER)).thenReturn(Optional.of(active));
        when(coordinator.updateConnectionNetworkProfile(eq("draft_foreign"), eq(0), anyString()))
                .thenReturn(Optional.of(active));
        when(profileStore.findConnection("conn_1")).thenReturn(Optional.of(connection("conn_1", "someone-else", "")));

        Response response = resource.updateConnectionNetworkProfile(
                null, new NetworkProfileUpdateRequest(0, VALID_PROFILE_JSON, null));

        assertEquals(200, response.getStatus());
        verify(profileStore, never()).updateConnection(any(LlmConnectionDto.class), anyInt());
    }

    // ---- updatePrompt / reset ----

    @Test
    void updatePromptRejectsABlankPromptWith400() {
        asOwner();

        assertEquals(
                400, resource.updatePrompt(null, new PromptUpdateRequest("   ")).getStatus());
        assertEquals(
                400, resource.updatePrompt(null, new PromptUpdateRequest(null)).getStatus());
        verify(vault, never()).storeSystemKey(anyString(), any(byte[].class));
    }

    @Test
    void updatePromptPersistsAndAuditsTheNewLength() {
        asOwner();

        assertEquals(
                Map.of("updated", true),
                resource.updatePrompt(null, new PromptUpdateRequest("be brief")).getEntity());
        verify(vault).storeSystemKey(eq(VaultConstants.SYSTEM_PROMPT_KEY), any(byte[].class));
        verify(auditService)
                .recordMandatory(
                        eq("settings"),
                        eq(OWNER),
                        eq("system"),
                        eq(SecurityEventType.SETTINGS_CHANGED),
                        contains("length=8"));
    }

    @Test
    void resetDiscardsTheActiveDraftAndAudits() {
        asOwner();
        when(coordinator.findActive(OWNER))
                .thenReturn(Optional.of(draft("draft_reset", OnboardingDraftState.DRAFT, List.of())));

        Response response = resource.reset(null);

        assertEquals(200, response.getStatus());
        verify(coordinator).discard("draft_reset", OWNER);
        verify(auditService)
                .recordMandatory(
                        eq("settings"),
                        eq(OWNER),
                        eq("system"),
                        eq(SecurityEventType.SETTINGS_RESET),
                        contains("discard-active-draft"));
    }

    @Test
    void resetSucceedsWhenNoDraftIsActive() {
        asOwner();
        when(coordinator.findActive(OWNER)).thenReturn(Optional.empty());

        assertEquals(200, resource.reset(null).getStatus());
        verify(coordinator, never()).discard(anyString(), anyString());
    }

    // ---- short-circuit operands ----
    // Each test below pins the second operand of an `&&` / `||` whose sibling is already exercised above. Without
    // them the class is line-complete but a seventh of its branches stay dark.

    @Test
    @DisplayName("backendLlmConfigured reports ready from provenance when the skip flag is off")
    void backendLlmConfiguredReportsReadyFromProvenance() {
        asOwner();
        when(provenance.resolve()).thenReturn(resolution(true));

        assertTrue(resource.getSettings(null).skipOnboarding());
    }

    @Test
    void updateVoiceToleratesAnAbsentBody() {
        asOwner();
        when(userPreferenceService.getEffectiveVoice(OWNER)).thenReturn(Optional.empty());
        when(userPreferenceService.getEffectiveOutputMode(OWNER)).thenReturn(Optional.empty());

        assertEquals(
                Map.of("voice", "default", "speechOutputMode", "TEXT_ONLY"),
                resource.updateVoice(null, null).getEntity());
        verify(userPreferenceService, never()).updatePreferences(anyString(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateProviderToleratesAnAbsentOrBlankCredential() {
        asOwner();
        OnboardingDraftDto staging = draft("draft_nulls", OnboardingDraftState.DRAFT, List.of());
        when(coordinator.getOrCreateFirstRunDraft(OWNER)).thenReturn(staging);
        when(coordinator.updateStep(anyString(), anyString(), anyList())).thenReturn(Optional.of(staging));
        when(coordinator.commitDraft("draft_nulls"))
                .thenReturn(Optional.of(draft("draft_nulls", OnboardingDraftState.COMPLETED, List.of())));

        // Both credential shapes must skip the vault store entirely.
        assertEquals(
                200,
                resource.updateProvider(null, new ProviderUpdateRequest("groq", null, null))
                        .getStatus());
        assertEquals(
                200,
                resource.updateProvider(null, new ProviderUpdateRequest("groq", null, "   "))
                        .getStatus());

        ArgumentCaptor<List<String>> staged = ArgumentCaptor.forClass(List.class);
        verify(coordinator, times(2)).updateStep(anyString(), anyString(), staged.capture());
        for (List<String> payload : staged.getAllValues()) {
            assertTrue(payload.get(0).contains(ModelDefaults.DEFAULT_GROQ_MODEL));
        }
        verify(vault, never()).storeSystemKey(anyString(), any(byte[].class));
    }

    @Test
    void updateProviderReturns409WhenTheCommitYieldsNothing() {
        asOwner();
        OnboardingDraftDto staging = draft("draft_no_commit", OnboardingDraftState.DRAFT, List.of());
        when(coordinator.getOrCreateFirstRunDraft(OWNER)).thenReturn(staging);
        when(coordinator.updateStep(anyString(), anyString(), anyList())).thenReturn(Optional.of(staging));
        when(coordinator.commitDraft("draft_no_commit")).thenReturn(Optional.empty());

        assertEquals(
                409,
                resource.updateProvider(null, new ProviderUpdateRequest("openai", "gpt-4o", null))
                        .getStatus());
    }

    @Test
    void getConnectionNetworkProfileIgnoresADraftEntryWithoutAProfile() {
        asOwner();
        // The staged connection carries no networkProfileJson key at all.
        when(coordinator.findActive(OWNER))
                .thenReturn(Optional.of(draft(
                        "draft_no_profile",
                        OnboardingDraftState.DRAFT,
                        List.of("{\"connections\":[{\"provider\":\"openai\"}]}"))));
        when(profileStore.listConnectionsByCreatedBy(OWNER)).thenReturn(null);

        assertEquals(
                "ALLOW_ALL",
                ((NetworkProfilePolicyDto)
                                resource.getConnectionNetworkProfile(null, 0).getEntity())
                        .egressMode());
    }

    @Test
    void getConnectionNetworkProfileIgnoresABlankDraftProfile() {
        asOwner();
        when(coordinator.findActive(OWNER))
                .thenReturn(Optional.of(draft(
                        "draft_blank_profile",
                        OnboardingDraftState.DRAFT,
                        List.of("{\"connections\":[{\"provider\":\"openai\",\"networkProfileJson\":\"   \"}]}"))));
        when(profileStore.listConnectionsByCreatedBy(OWNER)).thenReturn(List.of());

        assertEquals(
                "ALLOW_ALL",
                ((NetworkProfilePolicyDto)
                                resource.getConnectionNetworkProfile(null, 0).getEntity())
                        .egressMode());
    }

    @Test
    void getConnectionNetworkProfileIgnoresAnOutOfRangeIndex() {
        asOwner();
        when(coordinator.findActive(OWNER)).thenReturn(Optional.empty());
        when(profileStore.listConnectionsByCreatedBy(OWNER))
                .thenReturn(List.of(connection("conn_1", OWNER, VALID_PROFILE_JSON)));

        // A negative index and an index past the end both fall through to the default policy.
        assertEquals(
                "ALLOW_ALL",
                ((NetworkProfilePolicyDto)
                                resource.getConnectionNetworkProfile(null, -2).getEntity())
                        .egressMode());
        assertEquals(
                "ALLOW_ALL",
                ((NetworkProfilePolicyDto)
                                resource.getConnectionNetworkProfile(null, 7).getEntity())
                        .egressMode());
    }

    @Test
    void getConnectionNetworkProfileDefaultsWhenTheStoreBeanIsAbsent() {
        asOwner();
        when(coordinator.findActive(OWNER)).thenReturn(Optional.empty());
        resource.profileStoreInstance = null;

        assertEquals(
                "ALLOW_ALL",
                ((NetworkProfilePolicyDto)
                                resource.getConnectionNetworkProfile(null, 0).getEntity())
                        .egressMode());
    }

    @Test
    void updateConnectionNetworkProfileSkipsTheMirrorWhenTheStoreBeanIsAbsent() {
        asOwner();
        OnboardingDraftDto active = draft("draft_no_store", OnboardingDraftState.DRAFT, List.of(CONNECTION_PAYLOAD));
        when(coordinator.findActive(OWNER)).thenReturn(Optional.of(active));
        when(coordinator.updateConnectionNetworkProfile(eq("draft_no_store"), eq(0), anyString()))
                .thenReturn(Optional.of(active));
        resource.profileStoreInstance = null;

        assertEquals(
                200,
                resource.updateConnectionNetworkProfile(
                                null, new NetworkProfileUpdateRequest(0, VALID_PROFILE_JSON, null))
                        .getStatus());
    }
}
