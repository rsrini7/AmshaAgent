╔══════════════════════════════════════════════════════════════════╗
║     ARCCLAW — PHASE 83A: GUIDED ONBOARDING WIZARD                ║
║     Branch: P83A (cut from P82)                                  ║
╚══════════════════════════════════════════════════════════════════╝

Role: Senior Java Architect + Frontend TypeScript Engineer
Task: Implement the guided first-run onboarding wizard as two
      coordinated deliverables:
      (A) New Maven module `arcclaw-onboarding` — pure JSON REST API
      (B) New Lit component `arc-onboarding.ts` in `arcclaw-ui`

UPDATED CONTEXT vs ORIGINAL PROMPT:
  ✅ Phase 79 (arcclaw-ui scaffold) is DONE — arc-shell.ts exists
  ✅ Phase 82 is DONE — ChannelServices SPI exists, no Qute in channels
  ✅ AgentProducer pattern confirmed — no deity subclasses remain
  ✅ ApprovalGateway SPI is in arcclaw-channel-api
  ⚠️ arcclaw-onboarding module does NOT yet exist — create it fresh
  ⚠️ Do NOT add Qute dependency — P82 confirmed channels are Qute-free
  ⚠️ LlmModelFactory is in arcclaw-app — onboarding depends on arcclaw-app

════════════════════════════════════════════════════════════════════
CONSTRAINTS (non-negotiable)
════════════════════════════════════════════════════════════════════

1. All secrets MUST go through SecretVault — zero writes to
   application.yaml, .env, or any plain file.
   Refer: arcclaw-core/.../security/SecretVault.java
   Refer: arcclaw-core/.../security/VaultConstants.java

2. All POST endpoints return application/json — no HTMX, no Qute.

3. Step state is stored server-side in @SessionScoped CDI bean.
   The frontend is stateless between page reloads — it re-fetches
   GET /onboarding/status on every connectedCallback().

4. The wizard is idempotent — re-running it overwrites previous
   vault entries cleanly. No duplicate key errors.

5. HTTP 403 if canProceedTo(targetStep) is false — prevents
   URL-hacking and direct step posting.

6. ArchUnit rules from P82 must remain green:
   arcclaw-onboarding must NOT depend on com.arcclaw.channel.*
   arcclaw-onboarding may depend on com.arcclaw.core.*
   arcclaw-onboarding may depend on com.arcclaw.app.routing.*
     (for LlmModelFactory)

7. All 987 existing tests remain green.
   New module tests: ./mvnw clean test -pl arcclaw-onboarding

════════════════════════════════════════════════════════════════════
PART A — arcclaw-onboarding Maven Module
════════════════════════════════════════════════════════════════════

──────────────────────────────────────────────────────────────────
A1. Module setup
──────────────────────────────────────────────────────────────────

Create: arcclaw-onboarding/pom.xml

  <parent>com.arcclaw:arcclaw-parent</parent>
  <artifactId>arcclaw-onboarding</artifactId>

  Dependencies:
    arcclaw-core          (SecretVault, VaultConstants, AgentRoleEnum)
    arcclaw-app           (LlmModelFactory, McpConnectResource/
                           McpClientService)
    quarkus-rest          (RESTEasy Reactive)
    quarkus-rest-jackson
    quarkus-arc           (CDI)
    quarkus-undertow      (@SessionScoped requires servlet sessions)
    quarkus-junit5        (test scope)
    rest-assured          (test scope)

  DO NOT include: quarkus-qute, quarkus-websockets, anything channel

  Plugins:
    jandex-maven-plugin (index this module's beans)

  Create: arcclaw-onboarding/src/main/resources/META-INF/beans.xml
    bean-discovery-mode="annotated"

  Add to root pom.xml <modules>:
    <module>arcclaw-onboarding</module>

  Add to arcclaw-app/pom.xml <dependencies>:
    arcclaw-onboarding

──────────────────────────────────────────────────────────────────
A2. OnboardingStepProvider SPI
──────────────────────────────────────────────────────────────────

File: com.arcclaw.onboarding.spi.OnboardingStepProvider

/**
 * SPI for contributing additional wizard steps beyond the 5 core steps.
 * All @ApplicationScoped implementations are discovered via CDI Instance<>.
 * TelegramOnboardingProvider and BraveOnboardingProvider implement this
 * in future phases. No implementations exist in Phase 83A.
 */
public interface OnboardingStepProvider {
    String stepId();       // e.g. "telegram", "brave"
    int order();           // display order after core step 5
    String title();        // human-readable for progress bar
    boolean isConfigured(); // true if already fully set up
}

No implementations in Phase 83A — interface only.

──────────────────────────────────────────────────────────────────
A3. OnboardingSession (@SessionScoped)
──────────────────────────────────────────────────────────────────

File: com.arcclaw.onboarding.OnboardingSession

@SessionScoped
public class OnboardingSession implements Serializable {

    public enum Step {
        WELCOME(1), PROVIDER(2), CREDENTIALS(3),
        SYSTEM_PROMPT(4), MCP(5), COMPLETE(6);

        public final int number;
        Step(int n) { this.number = n; }

        public Step next() {
            Step[] vals = values();
            int idx = ordinal() + 1;
            return idx < vals.length ? vals[idx] : COMPLETE;
        }

        public boolean isAfter(Step other) {
            return this.number > other.number;
        }
    }

    private Step currentStep = Step.WELCOME;
    private String selectedProvider;  // "openai"|"groq"|"ollama"|"anthropic"|"claude-code"
    private String selectedModel;
    private boolean credentialsValidated = false;

    // Standard getters + setters for all fields.

    /**
     * Returns true only if target.number <= currentStep.number + 1.
     * Prevents skipping steps via direct HTTP calls.
     */
    public boolean canProceedTo(Step target) {
        return target.number <= currentStep.number + 1;
    }

    public void advance() {
        this.currentStep = currentStep.next();
    }
}

──────────────────────────────────────────────────────────────────
A4. DTOs
──────────────────────────────────────────────────────────────────

Package: com.arcclaw.onboarding.dto

// Response: current wizard state
public record OnboardingStatusDto(
    int step, String stepName, String provider,
    String model, boolean credentialsOk) {}

// Step 2 — provider selection
public record Step2Request(String provider, String model) {}

// Step 3 — credential validation
public record Step3Request(String apiKey) {}

// Step 4 — system prompt
public record Step4Request(String systemPrompt) {}

// Step 5 — MCP servers
public record McpServerRequest(
    String name,
    String transport,    // "http" | "stdio"
    String url,          // nullable for stdio
    String command,      // nullable for http
    String args,         // nullable, space-separated
    String env           // nullable, KEY=VALUE lines
) {}
public record Step5Request(List<McpServerRequest> mcpServers) {}

// Generic step response
public record StepResponse(int nextStep) {}

// Step 3 specific — includes validation result
public record Step3Response(int nextStep, boolean valid, String error) {}

// Step 5 specific — includes registered server names
public record Step5Response(int nextStep, List<String> registered) {}

// Final completion response
public record CompleteResponse(
    boolean complete, String chatUrl,
    String provider, String model,
    List<PluginStepDto> pluginSteps) {}

public record PluginStepDto(
    String stepId, String title, boolean isConfigured) {}

──────────────────────────────────────────────────────────────────
A5. OnboardingResource (JSON REST controller)
──────────────────────────────────────────────────────────────────

File: com.arcclaw.onboarding.OnboardingResource

@Path("/onboarding")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequestScoped
public class OnboardingResource {

    @Inject OnboardingSession session;
    @Inject SecretVault vault;
    @Inject LlmModelFactory modelFactory;
    @Inject Instance<OnboardingStepProvider> pluginProviders;

    // Inject McpClientService — locate its exact class in
    // arcclaw-app/src/main/java/com/arcclaw/app/mcp/ before injecting.
    // Use the service that McpConnectResource delegates to.

    private static final Map<String, String> DEFAULT_MODELS = Map.of(
        "groq",        "llama-3.1-8b-instant",
        "openai",      "gpt-4o-mini",
        "ollama",      "llama3.2",
        "anthropic",   "claude-haiku-4-5",
        "claude-code", "claude-sonnet-4-5"
    );

    // ── GET /onboarding/status ───────────────────────────────────
    // Returns current wizard state — always 200, no session guard.
    @GET @Path("/status")
    public OnboardingStatusDto status() {
        return new OnboardingStatusDto(
            session.getCurrentStep().number,
            session.getCurrentStep().name(),
            session.getSelectedProvider(),
            session.getSelectedModel(),
            session.isCredentialsValidated()
        );
    }

    // ── POST /onboarding/step/1 — Welcome ───────────────────────
    // Just advances. No body needed.
    @POST @Path("/step/1")
    public Response step1() {
        if (!session.canProceedTo(Step.PROVIDER))
            return Response.status(403).build();
        session.advance();
        return Response.ok(new StepResponse(2)).build();
    }

    // ── POST /onboarding/step/2 — Provider ──────────────────────
    @POST @Path("/step/2")
    public Response step2(Step2Request req) {
        if (!session.canProceedTo(Step.CREDENTIALS))
            return Response.status(403).build();
        String model = (req.model() != null && !req.model().isBlank())
            ? req.model()
            : DEFAULT_MODELS.getOrDefault(req.provider(), "");
        session.setSelectedProvider(req.provider());
        session.setSelectedModel(model);
        session.advance();
        return Response.ok(new StepResponse(3)).build();
    }

    // ── POST /onboarding/step/3 — Credentials ───────────────────
    // Validates the API key by making a live LLM test call.
    // 15-second timeout via @RunOnVirtualThread + MicroProfile timeout
    // OR wrap in a CompletableFuture with orTimeout(15, SECONDS).
    @POST @Path("/step/3")
    @RunOnVirtualThread
    public Response step3(Step3Request req) {
        if (!session.canProceedTo(Step.SYSTEM_PROMPT))
            return Response.status(403).build();

        String provider = session.getSelectedProvider();
        boolean isClaudeCode = "claude-code".equals(provider);

        try {
            if (!isClaudeCode) {
                // 1. Write key to vault BEFORE creating model
                //    (LlmModelFactory reads from vault)
                vault.storeSecret(VaultConstants.LLM_API_KEY,
                                  req.apiKey().toCharArray());
            }

            // 2. Create model via factory — factory reads vault for key
            var chatModel = modelFactory.createChatModel(
                provider, session.getSelectedModel());

            // 3. Test call with 15s timeout
            var future = CompletableFuture
                .supplyAsync(() -> chatModel.generate(
                    "Reply with the single word: ok"))
                .orTimeout(15, TimeUnit.SECONDS);

            String reply = future.get();

            if (reply != null && reply.toLowerCase().contains("ok")) {
                session.setCredentialsValidated(true);
                session.advance();
                return Response.ok(
                    new Step3Response(4, true, null)).build();
            } else {
                return Response.ok(
                    new Step3Response(3, false,
                        "Unexpected response: " + reply)).build();
            }

        } catch (TimeoutException e) {
            return Response.ok(
                new Step3Response(3, false,
                    "Validation timed out (15s). Check network or key."))
                .build();
        } catch (Exception e) {
            return Response.ok(
                new Step3Response(3, false, e.getMessage())).build();
        }
    }

    // ── POST /onboarding/step/4 — System Prompt ─────────────────
    @POST @Path("/step/4")
    public Response step4(Step4Request req) {
        if (!session.canProceedTo(Step.MCP))
            return Response.status(403).build();
        String prompt = (req.systemPrompt() == null
                         || req.systemPrompt().isBlank())
            ? "You are ArcClaw, a helpful AI assistant."
            : req.systemPrompt();
        vault.storeSecret(VaultConstants.SYSTEM_PROMPT_KEY,
                          prompt.toCharArray());
        // NOTE: if SYSTEM_PROMPT_KEY does not exist in VaultConstants,
        // add it: public static final String SYSTEM_PROMPT_KEY = "system.prompt";
        session.advance();
        return Response.ok(new StepResponse(5)).build();
    }

    // ── POST /onboarding/step/5 — MCP ───────────────────────────
    @POST @Path("/step/5")
    public Response step5(Step5Request req) {
        if (!session.canProceedTo(Step.COMPLETE))
            return Response.status(403).build();

        List<String> registered = new ArrayList<>();

        if (req.mcpServers() != null) {
            for (McpServerRequest s : req.mcpServers()) {
                try {
                    // Build McpServerConfig from McpServerRequest fields.
                    // Locate McpServerConfig in arcclaw-app/mcp/ package.
                    // Call mcpClientService.connect(config) or equivalent.
                    // On success: registered.add(s.name())
                    // On failure: log.warnf(...) only — do not abort loop
                } catch (Exception e) {
                    log.warnf("MCP registration failed for %s: %s",
                              s.name(), e.getMessage());
                }
            }
        }

        session.advance();
        return Response.ok(
            new Step5Response(6, registered)).build();
    }

    // ── GET /onboarding/complete ─────────────────────────────────
    @GET @Path("/complete")
    public CompleteResponse complete() {
        List<PluginStepDto> plugins = StreamSupport
            .stream(pluginProviders.spliterator(), false)
            .sorted(Comparator.comparingInt(OnboardingStepProvider::order))
            .map(p -> new PluginStepDto(
                p.stepId(), p.title(), p.isConfigured()))
            .toList();

        return new CompleteResponse(
            true, "/#/chat",
            session.getSelectedProvider(),
            session.getSelectedModel(),
            plugins
        );
    }
}

──────────────────────────────────────────────────────────────────
A6. VaultConstants — add if missing
──────────────────────────────────────────────────────────────────

In arcclaw-core/.../security/VaultConstants.java, verify or add:
    public static final String SYSTEM_PROMPT_KEY = "system.prompt";

Check existing constants before adding — do not duplicate.

──────────────────────────────────────────────────────────────────
A7. LlmModelFactory integration
──────────────────────────────────────────────────────────────────

Before writing step3():
  grep -n "createChatModel\|public.*ChatModel\|public.*create" \
    arcclaw-app/src/main/java/com/arcclaw/app/routing/LlmModelFactory.java

Use the EXACT method signature you find. Do not assume — read first.
The vault key write must happen BEFORE calling the factory, because
the factory reads from vault at model creation time.

For claude-code: the factory reads ~/.claude/credentials.json directly
via ClaudeCodeProviderFactory. No API key write needed. Skip the
vault.storeSecret() call for this provider and go directly to the
test call.

──────────────────────────────────────────────────────────────────
A8. McpClientService integration
──────────────────────────────────────────────────────────────────

Before writing step5():
  ls arcclaw-app/src/main/java/com/arcclaw/app/mcp/
  grep -n "connect\|register\|McpServerConfig" \
    arcclaw-app/src/main/java/com/arcclaw/app/mcp/*.java

Use the EXACT class names and method signatures you find.
Partial success is non-fatal — catch per-server, log warn, continue.

════════════════════════════════════════════════════════════════════
PART B — arc-onboarding Lit Component (arcclaw-ui)
════════════════════════════════════════════════════════════════════

──────────────────────────────────────────────────────────────────
B1. File locations
──────────────────────────────────────────────────────────────────

CREATE:  arcclaw-ui/src/components/arc-onboarding.ts
ADD TO:  arcclaw-ui/src/main.ts
           import './components/arc-onboarding';
UPDATE:  arcclaw-ui/src/components/arc-shell.ts
           #/onboarding route: render <arc-onboarding .apiKey=${...}>
           (replace any prior placeholder if present)

Before modifying arc-shell.ts, read it first:
  grep -n "onboarding\|apiKey\|route" \
    arcclaw-ui/src/components/arc-shell.ts

──────────────────────────────────────────────────────────────────
B2. Component spec
──────────────────────────────────────────────────────────────────

@customElement('arc-onboarding')
export class ArcOnboarding extends LitElement {

  // ── Properties ──────────────────────────────────────────────
  @property({ type: String }) apiKey = '';

  // ── State ────────────────────────────────────────────────────
  @state() private _status: OnboardingStatus | null = null;
  @state() private _loading = false;
  @state() private _error = '';

  // Step 2
  @state() private _provider = 'groq';
  @state() private _model = PROVIDER_DEFAULTS['groq'];

  // Step 3
  @state() private _apiKeyInput = '';
  @state() private _credError = '';

  // Step 4
  @state() private _systemPrompt = '';

  // Step 5
  @state() private _mcpName = '';
  @state() private _mcpTransport: 'http' | 'stdio' = 'http';
  @state() private _mcpUrl = '';
  @state() private _mcpCommand = '';
  @state() private _mcpServers: MCP[] = [];

  // ── Constants ────────────────────────────────────────────────
  // (same as before — use exact values from DEFAULT_MODELS in A5)
  const PROVIDER_DEFAULTS: Record<string, string> = {
    groq: 'llama-3.1-8b-instant',
    openai: 'gpt-4o-mini',
    ollama: 'llama3.2',
    anthropic: 'claude-haiku-4-5',
    'claude-code': 'claude-sonnet-4-5',
  };
  const STEP_TITLES = ['Welcome','Provider','Credentials',
                       'System Prompt','MCP','Complete'];

  // ── Lifecycle ────────────────────────────────────────────────
  async connectedCallback() {
    super.connectedCallback();
    await this._fetchStatus(); // always re-fetch on connect
  }

  // ── HTTP helpers ─────────────────────────────────────────────
  private async _fetchStatus() { ... }    // GET /onboarding/status
  private async _post(url, body) { ... }  // POST with auth header

  // ── Step renderers ───────────────────────────────────────────
  // _step1() — Welcome text + "Get Started →" button
  // _step2() — Radio buttons (groq/openai/ollama/anthropic/claude-code)
  //            + model name text input with auto-populated default
  //            "claude-code" shows "(uses local OAuth — no API key needed)"
  // _step3() — Conditional:
  //   claude-code: show OAuth notice, "Validate & Next →" enabled immediately
  //   others: password input, button disabled if blank
  //   On validation failure: show _credError inline, stay on step 3
  // _step4() — Textarea for system prompt, blank = default
  // _step5() — transport select, name input, url OR command input
  //            "+ Add Server" button adds to _mcpServers list
  //            Shows added servers as chips
  //            "Skip" button posts empty array
  //            "Register & Finish" / "Finish" button
  // _stepComplete() — ✅ ArcClaw is Ready! + provider/model info
  //                   Links to /#/chat and /#/agents

  // ── Progress bar ─────────────────────────────────────────────
  // 6 dots: done=green, current=blue, future=gray
  // Uses step number from _status.step

  // ── render() ─────────────────────────────────────────────────
  render() {
    if (!this._status) return html`<p>Loading…</p>`;
    const step = this._status.step;
    return html`
      <h2>Setup Wizard</h2>
      <div class="card">
        ${this._renderProgress(step)}
        ${this._error ? html`<p class="error">${this._error}</p>` : ''}
        ${step === 1 ? this._step1() : ''}
        ${step === 2 ? this._step2() : ''}
        ${step === 3 ? this._step3() : ''}
        ${step === 4 ? this._step4() : ''}
        ${step === 5 ? this._step5() : ''}
        ${step === 6 ? this._stepComplete() : ''}
      </div>
    `;
  }
}

──────────────────────────────────────────────────────────────────
B3. Styles
──────────────────────────────────────────────────────────────────

Scoped in static styles = css`...`:
  Use the existing arc-shell.ts color palette for consistency:
    Read arc-shell.ts first: grep -n "color\|background\|border"
      arcclaw-ui/src/components/arc-shell.ts | head -40
  Match the dark-mode terminal aesthetic already established.
  Core tokens needed:
    bg: #0d1117, surface: #161b22, border: #30363d
    text-primary: #c9d1d9, text-muted: #8b949e
    accent: #58a6ff, success: #3fb950, error: #f85149
  Progress bar: 6 dots, flex row, 4px height, border-radius 2px
  Card: border-radius 12px, padding 1.5rem
  Inputs: width 100%, bg #0d1117, border 1px solid #30363d
  Buttons: primary green #238636, secondary transparent

════════════════════════════════════════════════════════════════════
PART A — TESTS
════════════════════════════════════════════════════════════════════

OnboardingSessionTest (plain unit test, no Quarkus):
  □ Initial step is WELCOME (1)
  □ canProceedTo(PROVIDER) = true from WELCOME
  □ canProceedTo(CREDENTIALS) = false from WELCOME (skip attempt)
  □ advance() moves WELCOME→PROVIDER, PROVIDER→CREDENTIALS, etc.
  □ advance() from COMPLETE stays at COMPLETE
  □ canProceedTo() is symmetric: at PROVIDER, can go to CREDENTIALS
    but NOT to SYSTEM_PROMPT

OnboardingResourceTest (@QuarkusTest, use REST-assured):
  □ GET /onboarding/status → 200, step=1
  □ POST /onboarding/step/2 before step/1 → 403
  □ POST /onboarding/step/1 → 200, nextStep=2
  □ POST /onboarding/step/2 {"provider":"groq","model":""} →
      200, nextStep=3, model auto-populated to "llama-3.1-8b-instant"
  □ POST /onboarding/step/2 {"provider":"groq","model":"custom-model"} →
      200, model preserved as "custom-model"
  □ POST /onboarding/step/4 {"systemPrompt":""} →
      200, nextStep=5 (blank prompt gets default)
  □ POST /onboarding/step/5 {"mcpServers":[]} →
      200, nextStep=6, registered=[]
  □ GET /onboarding/complete → 200, complete=true, chatUrl="/#/chat"
  □ POST /onboarding/step/3 with claude-code provider, blank apiKey →
      handled gracefully (no NPE, no 500)

  NOTE: Step 3 LLM validation requires a real API key — mock the
  LlmModelFactory in test using @InjectMock or @ApplicationScoped
  test subclass that returns "ok" immediately.

════════════════════════════════════════════════════════════════════
DELIVERY CHECKLIST
════════════════════════════════════════════════════════════════════

□ arcclaw-onboarding module: pom.xml, beans.xml, jandex plugin
□ OnboardingStepProvider SPI interface
□ OnboardingSession @SessionScoped with canProceedTo() + advance()
□ All DTOs as Java records in com.arcclaw.onboarding.dto
□ OnboardingResource: GET /status, POST /step/1-5, GET /complete
□ Step 3: live LLM validation, 15s timeout, claude-code shortcut
□ Step 5: McpClientService integration, partial failure non-fatal
□ All secrets via SecretVault — zero plain-text writes
□ SYSTEM_PROMPT_KEY added to VaultConstants if missing
□ arcclaw-onboarding added to root pom.xml <modules>
□ arcclaw-onboarding added to arcclaw-app/pom.xml <dependencies>
□ arc-onboarding.ts created in arcclaw-ui/src/components/
□ arc-onboarding imported in arcclaw-ui/src/main.ts
□ arc-shell.ts #/onboarding route renders <arc-onboarding>
□ OnboardingSessionTest: all 6 unit tests green
□ OnboardingResourceTest: all @QuarkusTest integration tests green
□ LlmModelFactory mocked in test — no real API calls
□ ./mvnw clean test -pl arcclaw-onboarding → all green
□ ./mvnw clean test → all 987 existing tests still green
□ npm test in arcclaw-ui → still green

════════════════════════════════════════════════════════════════════
FINAL VERIFY COMMAND
════════════════════════════════════════════════════════════════════

./mvnw clean verify \
  -pl arcclaw-channel-api,arcclaw-channel-telegram,\
arcclaw-channel-web,arcclaw-core,arcclaw-app,arcclaw-onboarding \
  -am

Expected: Tests run: 1000+ | Failures: 0 | Errors: 0 | Skipped: 1
