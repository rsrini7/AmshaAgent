╔══════════════════════════════════════════════════════════════════╗
║   ARCCLAW — PHASE 84: SETTINGS & RE-CONFIGURATION PANEL         ║
║   Branch: P84 (cut from P83A)                                   ║
╚══════════════════════════════════════════════════════════════════╝

Context from P83A (completed):
  ✅ arcclaw-onboarding module — 5-step wizard REST API
  ✅ arcclaw-ui arc-onboarding.ts — Lit wizard component
  ✅ arc-shell.ts — constructor restores session from localStorage
  ✅ ApiKeyAuthFilter — /onboarding/* bypass
  ✅ OnboardingSessionRegistry — Caffeine-backed in-memory sessions

PROBLEM P84 solves:
  After onboarding, users have no way to:
  (a) Change their LLM provider or API key
  (b) Edit their system prompt
  (c) Re-run onboarding if their key expires
  (d) Disconnect / reset their configuration

  Also: if the Quarkus server restarts, the Caffeine session cache is
  wiped. A user who completed onboarding before restart has a valid
  localStorage key but cannot re-enter the wizard (step guard blocks
  them at step > 1 because the session is fresh/blank again).

════════════════════════════════════════════════════════════════════
CONSTRAINTS (all P83A rules apply plus):
════════════════════════════════════════════════════════════════════

1. Settings endpoints live in arcclaw-onboarding module.
   No new module needed.

2. Settings endpoints ARE protected by X-ARCCLAW-API-KEY header.
   They are NOT under /onboarding/* — they live under /settings/*.
   ApiKeyAuthFilter already protects /settings/* (no bypass needed).

3. All vault reads/writes go through SecretVault only.
   No direct file or env reads.

4. The UI settings panel is a NEW Lit component: arc-settings.ts
   Added as a new route #/settings in arc-shell.ts _renderRoute().

5. Re-onboarding = clear the Caffeine session + reset step to 1.
   The user goes back through the full wizard.
   Existing vault secrets are NOT wiped until new ones are saved.

════════════════════════════════════════════════════════════════════
PART A — Backend: SettingsResource in arcclaw-onboarding
════════════════════════════════════════════════════════════════════

──────────────────────────────────────────────────────────────────
A1. New DTOs in com.arcclaw.onboarding.dto
──────────────────────────────────────────────────────────────────

// Current config summary — returned by GET /settings
public record SettingsDto(
    String provider,
    String model,
    String systemPrompt,    // may be truncated to 200 chars for display
    boolean hasApiKey,      // true if vault has LLM_API_KEY set
    String appVersion       // from MavenProjectVersion or quarkus.application.version
) {}

// PATCH /settings/provider — change LLM provider + model + key
public record ProviderUpdateRequest(
    String provider,
    String model,
    String apiKey           // nullable for claude-code / ollama
) {}

// PATCH /settings/prompt — change system prompt only
public record PromptUpdateRequest(String systemPrompt) {}

──────────────────────────────────────────────────────────────────
A2. SettingsResource
──────────────────────────────────────────────────────────────────

File: com.arcclaw.onboarding.SettingsResource

@Path("/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class SettingsResource {

  @Inject SecretVault vault;
  @Inject LlmValidationGateway llmValidationGateway;
  @Inject OnboardingSessionRegistry registry;

  @ConfigProperty(name = "quarkus.application.version",
                  defaultValue = "dev")
  String appVersion;

  // ── GET /settings ──────────────────────────────────────
  // Returns current configuration — key presence only, never value.
  @GET
  public SettingsDto getSettings() {
    String provider = readVaultString(VaultConstants.LLM_PROVIDER_KEY, "");
    String model    = readVaultString(VaultConstants.LLM_MODEL_KEY, "");
    String prompt   = readVaultString(VaultConstants.SYSTEM_PROMPT_KEY, "");
    boolean hasKey  = vault.hasSecret(VaultConstants.LLM_API_KEY);

    // Truncate prompt for display — full value only via /settings/prompt GET
    String displayPrompt = prompt.length() > 200
        ? prompt.substring(0, 200) + "…"
        : prompt;

    return new SettingsDto(provider, model, displayPrompt,
                           hasKey, appVersion);
  }

  // ── GET /settings/prompt ───────────────────────────────
  // Returns full system prompt (authenticated route).
  @GET @Path("/prompt")
  public Response getPrompt() {
    String prompt = readVaultString(VaultConstants.SYSTEM_PROMPT_KEY, "");
    return Response.ok(Map.of("systemPrompt", prompt)).build();
  }

  // ── PATCH /settings/provider ───────────────────────────
  // Validates and stores new LLM provider + key.
  @PATCH @Path("/provider")
  @RunOnVirtualThread
  public Response updateProvider(ProviderUpdateRequest req) {
    if (req.provider() == null || req.provider().isBlank())
      return Response.status(400)
          .entity(Map.of("error", "provider is required")).build();

    boolean isClaudeCode = "claude-code".equals(req.provider());
    boolean isOllama     = "ollama".equals(req.provider());

    // Validate key for providers that need one
    if (!isClaudeCode && !isOllama) {
      if (req.apiKey() == null || req.apiKey().isBlank())
        return Response.status(400)
            .entity(Map.of("error", "apiKey is required for "
                            + req.provider())).build();
      try {
        boolean valid = llmValidationGateway.validate(
            req.provider(), req.model(), req.apiKey());
        if (!valid)
          return Response.status(422)
              .entity(Map.of("error",
                  "API key validation failed for " + req.provider()))
              .build();
      } catch (Exception e) {
        return Response.status(422)
            .entity(Map.of("error", e.getMessage())).build();
      }
    }

    // Persist — overwrite existing vault entries
    vault.storeSystemKey(VaultConstants.LLM_PROVIDER_KEY,
        req.provider().getBytes(StandardCharsets.UTF_8));
    vault.storeSystemKey(VaultConstants.LLM_MODEL_KEY,
        (req.model() != null ? req.model() : "")
            .getBytes(StandardCharsets.UTF_8));
    if (!isClaudeCode && req.apiKey() != null) {
      vault.storeSystemKey(VaultConstants.LLM_API_KEY,
          req.apiKey().getBytes(StandardCharsets.UTF_8));
    }

    return Response.ok(Map.of("updated", true)).build();
  }

  // ── PATCH /settings/prompt ─────────────────────────────
  @PATCH @Path("/prompt")
  public Response updatePrompt(PromptUpdateRequest req) {
    if (req.systemPrompt() == null || req.systemPrompt().isBlank())
      return Response.status(400)
          .entity(Map.of("error", "systemPrompt is required")).build();
    vault.storeSystemKey(VaultConstants.SYSTEM_PROMPT_KEY,
        req.systemPrompt().getBytes(StandardCharsets.UTF_8));
    return Response.ok(Map.of("updated", true)).build();
  }

  // ── POST /settings/reset ───────────────────────────────
  // Clears all onboarding sessions → forces re-onboarding on next visit.
  // Does NOT wipe vault secrets — existing agents keep working until
  // user completes re-onboarding with new credentials.
  @POST @Path("/reset")
  public Response reset() {
    registry.invalidateAll();  // Caffeine .invalidateAll()
    return Response.ok(Map.of("reset", true,
        "message", "Onboarding sessions cleared. "
                  + "Refresh to re-run the wizard.")).build();
  }

  // ── Helper ─────────────────────────────────────────────
  private String readVaultString(String key, String fallback) {
    try {
      byte[] bytes = vault.readSystemKey(key);
      return bytes != null ? new String(bytes, StandardCharsets.UTF_8)
                           : fallback;
    } catch (Exception e) {
      return fallback;
    }
  }
}

NOTE: Add VaultConstants.LLM_MODEL_KEY if missing:
  public static final String LLM_MODEL_KEY = "llm.model";

NOTE: Add OnboardingSessionRegistry.invalidateAll():
  public void invalidateAll() {
    sessions.invalidateAll();
  }

NOTE: vault.hasSecret(key) and vault.readSystemKey(key) —
  check SecretVault for exact method names before using.
  If hasSecret() doesn't exist, use:
    boolean hasKey = vault.readSystemKey(key) != null;

════════════════════════════════════════════════════════════════════
PART B — Frontend: arc-settings.ts
════════════════════════════════════════════════════════════════════

File: arcclaw-ui/src/components/arc-settings.ts

New @customElement('arc-settings') LitElement.
Props: @property() apiKey = '';

Three sections rendered as tabs:
  Tab 1 "LLM Provider" — shows current provider + model,
    "Change Provider" button → same provider card grid as onboarding
    step 2 + API key input + inline Validate & Save button
  Tab 2 "System Prompt" — full textarea, Save button
  Tab 3 "Danger Zone" — single "Reset & Re-Onboard" button
    with confirmation dialog before calling POST /settings/reset

API calls — standalone functions in client.ts (NOT in ArcClient):

  export async function getSettings(apiKey: string) {
    const res = await fetch('/settings', {
      headers: { 'X-ARCCLAW-API-KEY': apiKey }
    });
    if (!res.ok) throw new Error(`settings ${res.status}`);
    return res.json();
  }

  export async function patchProvider(
      apiKey: string, body: object) {
    const res = await fetch('/settings/provider', {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
        'X-ARCCLAW-API-KEY': apiKey
      },
      body: JSON.stringify(body)
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.error || `PATCH failed: ${res.status}`);
    }
    return res.json();
  }

  export async function patchPrompt(
      apiKey: string, prompt: string) {
    const res = await fetch('/settings/prompt', {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
        'X-ARCCLAW-API-KEY': apiKey
      },
      body: JSON.stringify({ systemPrompt: prompt })
    });
    if (!res.ok) throw new Error(`prompt update failed`);
    return res.json();
  }

  export async function resetOnboarding(apiKey: string) {
    const res = await fetch('/settings/reset', {
      method: 'POST',
      headers: { 'X-ARCCLAW-API-KEY': apiKey }
    });
    if (!res.ok) throw new Error(`reset failed`);
    return res.json();
  }

Apply existing CSS classes from tokens.css:
  field-input, field-textarea, btn-primary, btn-ghost,
  status-msg success/error, field-label, provider-card/selected
  Match the wizard card look — same border/shadow/radius.

════════════════════════════════════════════════════════════════════
PART C — arc-shell.ts: add #/settings route
════════════════════════════════════════════════════════════════════

In _renderRoute():

  case '#/settings':
    return html`<arc-settings
      .apiKey="${this._apiKey}"
      @navigate="${(e: CustomEvent) =>
        this._navigate(e.detail.route)}"
    ></arc-settings>`;

In arc-nav.ts (the navigation bar component):
  Add a Settings link that dispatches navigate event
  to '#/settings'. Use a gear icon (⚙ or Lucide settings).

════════════════════════════════════════════════════════════════════
PART D — Vite proxy
════════════════════════════════════════════════════════════════════

Add to vite.config.ts server.proxy — same target as /onboarding:
  '/settings': {
    target: 'http://localhost:<SAME_PORT_AS_API>',
    changeOrigin: true
  }

════════════════════════════════════════════════════════════════════
PART E — ApiKeyAuthFilter: /settings NOT bypassed
════════════════════════════════════════════════════════════════════

Verify ApiKeyAuthFilter does NOT bypass /settings.
The current bypass list is:
  path.startsWith("/info") ||
  path.startsWith("/api/v1/info") ||
  path.startsWith("/onboarding")

/settings is NOT in this list — it should stay protected.
DO NOT add /settings to the bypass. ✅

════════════════════════════════════════════════════════════════════
TESTS
════════════════════════════════════════════════════════════════════

Add SettingsResourceTest to arcclaw-onboarding module:
  - GET /settings → 200 with SettingsDto shape
  - PATCH /settings/prompt → 200 updated=true, vault has new value
  - PATCH /settings/provider with bad key → 422
  - POST /settings/reset → 200 reset=true, registry is cleared

All tests use @QuarkusTest + REST-Assured.
Mock LlmValidationGateway with @InjectMock for step3/PATCH tests.

Run after implementation:
  ./mvnw clean test -Dtest=SettingsResourceTest \
    -pl arcclaw-onboarding
════════════════════════════════════════════════════════════════════