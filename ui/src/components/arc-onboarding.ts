import { LitElement, css, html } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import {
  ArcApiError,
  ArcClient,
  clearPersistedOwnerApiKey,
  persistOwnerApiKey,
  readPersistedOwnerApiKey,
} from '../api/client';
import { O1DraftPayload, ConnectionDraft, OnboardingStatusDto, ProviderDescriptor } from '../api/types';
import { formStyles } from './arc-onboarding-styles';
import './onboarding/arc-onboarding-plan-stepper';
import './onboarding/arc-onboarding-step-welcome';
import './onboarding/arc-onboarding-step-connections';
import './onboarding/arc-onboarding-step-concern-routing';
import './onboarding/arc-onboarding-step-persona-routing';
import './onboarding/arc-onboarding-step-mcp';
import './onboarding/arc-onboarding-step-integrations';
import './onboarding/arc-onboarding-step-advanced-settings';
import './onboarding/arc-onboarding-step-memory';
import './onboarding/arc-onboarding-step-security';
import './onboarding/arc-onboarding-step-channels';
import './onboarding/arc-onboarding-step-identity';
import './onboarding/arc-onboarding-step-review';

@customElement('arc-onboarding')
export class ArcOnboarding extends LitElement {
  private _client = new ArcClient('/api/v1');

  @state() private _draftId = '';
  @state() private _currentStepId = 'welcome';
  @state() private _planStages: string[] = [];
  @state() private _draftPayload: O1DraftPayload = {
    connections: [],
    concernRoutes: { agentLoop: [], compaction: [], classifier: [], embedding: [] }
  };
  @state() private _catalog: ProviderDescriptor[] = [];
  @state() private _modelDefaults: Record<string, { defaultModel: string; defaultBaseUrl?: string }> = {};
  @state() private _loading = false;
  @state() private _error = '';
  @state() private _vaultPriority: boolean | null = null;
  @state() private _effectiveSource: string | null = null;
  @state() private _credentialState: string | null = null;
  @state() private _credentialSource: string | null = null;
  @state() private _isAdvanced = false;
  @state() private _bootstrapState: 'CLAIMING' | 'CLAIMED' | null = null;
  @state() private _ownerApiKey = '';

  static styles = [
    formStyles,
    css`
      :host {
        display: flex;
        align-items: flex-start;
        justify-content: center;
        min-height: 100%;
        box-sizing: border-box;
        background: var(--bg-base);
        font-family: var(--font-family);
        padding: var(--space-6) var(--space-4);
      }

      .wizard-card {
        width: 100%;
        max-width: 520px;
        background: var(--bg-surface);
        border: 1px solid var(--border-card);
        border-radius: var(--radius-xl);
        box-shadow: var(--shadow-lg), var(--shadow-glow);
        overflow: hidden;
        position: relative;
        margin-block: auto;
        animation: card-in 0.4s cubic-bezier(0.16, 1, 0.3, 1) both;
        transition: max-width var(--transition);
      }

      .wizard-card.wide {
        max-width: 800px;
      }

      [data-theme='dark'] .wizard-card {
        background: linear-gradient(145deg, #13151f 0%, #111318 100%);
        border-color: rgba(255, 255, 255, 0.06);
      }

      @keyframes card-in {
        from {
          opacity: 0;
          transform: translateY(20px) scale(0.98);
        }
        to {
          opacity: 1;
          transform: translateY(0) scale(1);
        }
      }

      .wizard-card::before {
        content: '';
        position: absolute;
        top: 0;
        left: 0;
        right: 0;
        height: 3px;
        background: linear-gradient(90deg, var(--accent) 0%, var(--accent-secondary) 100%);
      }

      .wizard-header {
        padding: var(--space-8) var(--space-8) var(--space-4);
        text-align: center;
      }

      .brand-mark {
        width: 44px;
        height: 44px;
        margin: 0 auto var(--space-4);
        background: linear-gradient(135deg, var(--accent) 0%, var(--accent-secondary) 100%);
        border-radius: var(--radius-md);
        display: flex;
        align-items: center;
        justify-content: center;
        box-shadow: 0 8px 24px var(--accent-glow);
      }

      .brand-mark svg {
        color: #fff;
      }

      .wizard-title {
        font-size: var(--font-size-2xl);
        font-weight: var(--font-weight-bold);
        color: var(--text-primary);
        letter-spacing: -0.02em;
        line-height: 1.2;
        margin-bottom: var(--space-2);
      }

      .wizard-subtitle {
        font-size: var(--font-size-sm);
        color: var(--text-muted);
        line-height: 1.5;
      }

      .wizard-body {
        padding: 0 var(--space-8) var(--space-8);
      }

      .wizard-footer {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: var(--space-4) var(--space-8);
        background: color-mix(in srgb, var(--bg-elevated) 40%, transparent);
        border-top: 1px solid var(--border);
      }

      .mode-toggle {
        font-size: var(--font-size-xs);
        color: var(--text-muted);
        background: none;
        border: none;
        cursor: pointer;
        font-weight: var(--font-weight-medium);
        text-decoration: underline;
      }

      .mode-toggle:hover {
        color: var(--accent);
      }

      .bootstrap-recovery {
        display: flex;
        flex-direction: column;
        gap: var(--space-3);
        margin-top: var(--space-4);
        padding: var(--space-4);
        background: var(--bg-elevated);
        border: 1px solid var(--border-card);
        border-radius: var(--radius-md);
      }

      .bootstrap-recovery p {
        color: var(--text-muted);
        font-size: var(--font-size-xs);
        line-height: 1.5;
      }

      .bootstrap-recovery input {
        width: 100%;
        padding: var(--space-3);
        color: var(--text-primary);
        background: var(--bg-surface);
        border: 1px solid var(--border);
        border-radius: var(--radius-md);
        font: inherit;
      }

      .bootstrap-recovery-actions {
        display: flex;
        flex-wrap: wrap;
        gap: var(--space-3);
      }

      .sr-only {
        position: absolute;
        width: 1px;
        height: 1px;
        padding: 0;
        margin: -1px;
        overflow: hidden;
        clip: rect(0, 0, 0, 0);
        white-space: nowrap;
        border: 0;
      }
    `
  ];

  async connectedCallback() {
    super.connectedCallback();
    await this._initDraft();
  }

  private async _initDraft() {
    this._loading = true;
    this._error = '';
    this._draftId = '';
    this._planStages = [];
    this._vaultPriority = null;
    this._effectiveSource = null;
    this._credentialState = null;
    this._credentialSource = null;
    this._bootstrapState = null;
    try {
      let status: OnboardingStatusDto | undefined;
      const attemptedOwnerApiKey = readPersistedOwnerApiKey();
      try {
        status = await this._client.onboardingStatus();
      } catch (error) {
        // A clean deployment deliberately protects onboarding/status until the
        // first owner exists. Bootstrap is the unauthenticated entry point.
        if (!(error instanceof ArcApiError) || error.status !== 401) throw error;
        if (attemptedOwnerApiKey) {
          try {
            // Test the HttpOnly cookie without deleting the persisted key first.
            // Only a successful cookie-authenticated response proves the stored
            // header is stale and safe to remove.
            status = await new ArcClient('/api/v1', undefined, false).onboardingStatus();
            clearPersistedOwnerApiKey(attemptedOwnerApiKey);
          } catch (retryError) {
            if (!(retryError instanceof ArcApiError) || retryError.status !== 401) {
              throw retryError;
            }
          }
        }
      }
      this._updateConfigurationStatus(status);
      if (status?.credentialsOk || status?.skipOnboarding) {
        const effectiveKey = attemptedOwnerApiKey || readPersistedOwnerApiKey() || 'authenticated';
        this.dispatchEvent(
          new CustomEvent('api-key-set', {
            bubbles: true,
            composed: true,
            detail: { key: effectiveKey }
          })
        );
        return;
      }

      const boot = await this._client.getBootstrapStatus();
      if (boot.status === 'UNCLAIMED' || boot.status === 'CREDENTIAL_ISSUED') {
        const claim = await this._client.claimFirstOwner();
        if (typeof claim?.apiKey === 'string' && claim.apiKey.trim()) {
          persistOwnerApiKey(claim.apiKey);
          // Keep the issued credential in memory for this claim flow even if
          // browser storage is restricted; the HttpOnly cookie remains primary.
          this._client = new ArcClient('/api/v1', claim.apiKey.trim());
        }
      } else if (boot.status === 'CLAIMING') {
        this._bootstrapState = 'CLAIMING';
        throw new Error('Bootstrap ownership is currently being claimed; retry in a moment.');
      } else if (boot.status === 'CLAIMED') {
        this._bootstrapState = 'CLAIMED';
      }

      status = await this._client.onboardingStatus();
      this._updateConfigurationStatus(status);
      if (status.credentialsOk || status.skipOnboarding) {
        const effectiveKey = readPersistedOwnerApiKey() || 'authenticated';
        this.dispatchEvent(
          new CustomEvent('api-key-set', {
            bubbles: true,
            composed: true,
            detail: { key: effectiveKey },
          }),
        );
        return;
      }

      this._catalog = await this._client.getProviderCatalog();
      try {
        this._modelDefaults = await this._client.getModelDefaults();
      } catch (_) {
        // Model defaults endpoint may not be available in older backends
      }

      const res = await this._client.createOrResumeDraft();
      this._draftId = res.draftId;
      this._currentStepId = res.currentStepId;
      this._planStages = res.planFingerprint.split('->');
      if (res.selectedNonSecretIds && res.selectedNonSecretIds.length > 0) {
        try {
          const parsed = JSON.parse(res.selectedNonSecretIds[0]);
          if (!parsed.mcpServers && parsed.mcp?.servers) {
            parsed.mcpServers = parsed.mcp.servers.map((server: any) => ({
              name: server.name,
              category: server.category || 'NETWORK',
              transport: 'sse',
              url: server.endpoint,
              headerVaultRefs: server.headerVaultRefs || {},
            }));
          }
          this._draftPayload = parsed;
        } catch (_) {
          // Payload is clean/empty
        }
      }
    } catch (e: any) {
      if (this._bootstrapState === 'CLAIMED' && e instanceof ArcApiError && e.status === 401) {
        this._error = 'Bootstrap is already claimed. Sign in with the owner API key to continue.';
      } else {
        this._error = `Failed to load draft: ${e.message}`;
      }
    } finally {
      this._loading = false;
    }
  }

  private _updateConfigurationStatus(status?: OnboardingStatusDto) {
    if (!status) return;
    if (typeof status.vaultPriority === 'boolean') this._vaultPriority = status.vaultPriority;
    this._effectiveSource = status.effectiveSource || null;
    this._credentialState = status.credentialState || null;
    this._credentialSource = status.credentialSource || null;
  }

  private async _recoverLocalOwner() {
    this._loading = true;
    this._error = '';
    try {
      const recovery = await this._client.recoverLocalOwner();
      if (typeof recovery?.apiKey !== 'string' || !recovery.apiKey.trim()) {
        throw new Error('Local recovery did not return an owner credential.');
      }
      persistOwnerApiKey(recovery.apiKey);
      this._client = new ArcClient('/api/v1', recovery.apiKey.trim());
      await this._initDraft();
    } catch (error: any) {
      if (error instanceof ArcApiError && error.status === 403) {
        this._error = 'Local owner recovery is disabled. Set AMSHA_BOOTSTRAP_LOCAL_RECOVERY=true and restart.';
      } else {
        this._error = `Local owner recovery failed: ${error.message}`;
      }
    } finally {
      this._loading = false;
    }
  }

  private async _signInOwner() {
    const apiKey = this._ownerApiKey.trim();
    if (!apiKey) {
      this._error = 'Enter the owner API key to continue.';
      return;
    }
    persistOwnerApiKey(apiKey);
    this._client = new ArcClient('/api/v1', apiKey);
    await this._initDraft();
  }

  private async _saveAndNavigate(targetStepId: string, payloadUpdates?: Partial<O1DraftPayload>) {
    if (!this._draftId) {
      this._error = 'Onboarding is not ready. Retry after authentication completes.';
      return;
    }
    this._loading = true;
    this._error = '';
    try {
      const newPayload = this._withMcpExtensionPayload({ ...this._draftPayload, ...payloadUpdates });
      const serialized = JSON.stringify(newPayload);
      
      const res = await this._client.updateDraftStep(this._draftId, targetStepId, {
        selectedNonSecretIds: [serialized]
      });
      
      this._currentStepId = res.currentStepId;
      if (res.selectedNonSecretIds && res.selectedNonSecretIds.length > 0) {
        try {
          const parsed = JSON.parse(res.selectedNonSecretIds[0]);
          if (parsed.mcp?.servers) {
            parsed.mcpServers = parsed.mcp.servers.map((server: any) => ({
              name: server.name,
              category: server.category || 'NETWORK',
              transport: 'sse',
              url: server.endpoint,
              headerVaultRefs: server.headerVaultRefs || {},
            }));
          }
          this._draftPayload = parsed;
        } catch (_) {
          this._draftPayload = { ...newPayload, mcpServers: this._draftPayload.mcpServers };
        }
      } else {
        this._draftPayload = { ...newPayload, mcpServers: this._draftPayload.mcpServers };
      }
      if (this._draftPayload.mcpServers) {
        this._draftPayload.mcpServers = this._draftPayload.mcpServers.map(s => {
          const { headers: _, ...rest } = s;
          return rest;
        });
      }
      this.updateComplete.then(() => {
        const target = this.shadowRoot?.querySelector('h2, .step-title, input, button.btn-primary');
        (target as HTMLElement)?.focus?.();
      });
    } catch (e: any) {
      this._error = e.message;
    } finally {
      this._loading = false;
    }
  }

  private async _discard() {
    if (!confirm('Are you sure you want to discard your progress and restart?')) return;
    this._loading = true;
    try {
      await this._client.discardDraft();
      await this._initDraft();
    } catch (e: any) {
      this._error = e.message;
    } finally {
      this._loading = false;
    }
  }

  private async _commit() {
    this._loading = true;
    this._error = '';
    try {
      await this._client.commitDraft(this._draftId);
      const effectiveKey = readPersistedOwnerApiKey() || 'authenticated';
      this.dispatchEvent(
        new CustomEvent('api-key-set', {
          bubbles: true,
          composed: true,
          detail: { key: effectiveKey }
        })
      );
    } catch (e: any) {
      this._error = `Commit failed: ${e.message}`;
    } finally {
      this._loading = false;
    }
  }

  private _navigateNext() {
    const idx = this._planStages.indexOf(this._currentStepId);
    if (idx !== -1 && idx < this._planStages.length - 1) {
      let nextStep = this._planStages[idx + 1];
      if (!this._isAdvanced) {
        while (nextStep !== 'review' && nextStep !== 'complete' && idx < this._planStages.length - 1) {
          const nextIdx = this._planStages.indexOf(nextStep);
          nextStep = this._planStages[nextIdx + 1];
        }
      }
      this._saveAndNavigate(nextStep);
    }
  }

  private _navigateBack() {
    const idx = this._planStages.indexOf(this._currentStepId);
    if (idx > 0) {
      let prevStep = this._planStages[idx - 1];
      if (!this._isAdvanced) {
        while (prevStep !== 'connections' && prevStep !== 'welcome' && prevStep !== 'routes') {
          const prevIdx = this._planStages.indexOf(prevStep);
          prevStep = this._planStages[prevIdx - 1];
        }
      }
      this._saveAndNavigate(prevStep);
    }
  }

  private _handleQuickStart() {
    // Quick Start must never stage a key-required provider without a key. Prefer
    // Ollama, then any catalog provider that explicitly needs no API key.
    const quickProvider = this._catalog.find(p => p.id === 'ollama' && !p.requiresApiKey && p.embeddingCapable)
      || this._catalog.find(p => !p.requiresApiKey && p.embeddingCapable)
      || this._catalog.find(p => p.id === 'ollama' && !p.requiresApiKey)
      || this._catalog.find(p => !p.requiresApiKey);
    if (!quickProvider) {
      this._error = 'No keyless provider is available. Use Advanced setup to configure a provider.';
      this._isAdvanced = true;
      this._saveAndNavigate('connections');
      return;
    }
    const defaultModel = quickProvider.modelCatalog?.[0]?.id
      || this._modelDefaults[quickProvider.id]?.defaultModel
      || '';
    const defaultConn: ConnectionDraft = {
      provider: quickProvider.id,
      modelId: defaultModel,
      contextWindow: 8192,
      structuredOutputMode: 'NATIVE',
      capabilities: ['tool-calling']
    };

    const preseededPayload: O1DraftPayload = {
      connections: [defaultConn],
      concernRoutes: {
        agentLoop: [0],
        compaction: [0],
        classifier: [0],
      embedding: quickProvider.embeddingCapable ? [0] : []
      }
    };

    this._saveAndNavigate('connections', preseededPayload);
  }

  private _handleAdvancedStart() {
    this._isAdvanced = true;
    this._saveAndNavigate('connections');
  }

  private _handleUpdateConnections(e: CustomEvent) {
    this._draftPayload = { ...this._draftPayload, connections: e.detail.connections };
  }

  private _handleUpdateRoutes(e: CustomEvent) {
    this._draftPayload = { ...this._draftPayload, concernRoutes: e.detail.concernRoutes };
  }

  private _handleUpdatePersonaRoutes(e: CustomEvent) {
    this._draftPayload = { ...this._draftPayload, personaRoutes: e.detail.personaRoutes };
  }

  private _handleUpdateMcp(e: CustomEvent) {
    this._draftPayload = { ...this._draftPayload, mcpServers: e.detail.mcpServers };
  }

  private _withMcpExtensionPayload(payload: O1DraftPayload): O1DraftPayload {
    const payloadWithoutUiOnlyMcp = { ...payload };
    delete payloadWithoutUiOnlyMcp.mcpServers;
    const servers = (payload.mcpServers || []).map(server => {
      const s: any = {
        name: server.name.trim(),
        endpoint: (server.url || '').trim(),
        category: server.category || 'NETWORK',
      };
      if (server.transport) s.transport = server.transport;
      if (server.command) s.command = server.command;
      if (server.args) s.args = [...server.args];
      if (server.envVars) s.envVars = { ...server.envVars };
      if (server.headerVaultRefs && Object.keys(server.headerVaultRefs).length > 0) {
        s.headerVaultRefs = { ...server.headerVaultRefs };
      }
      if (server.headers && Object.keys(server.headers).length > 0) {
        s.headers = { ...server.headers };
      }
      return s;
    });
    return { ...payloadWithoutUiOnlyMcp, mcp: { servers } };
  }

  private _handleUpdateIntegrations(e: CustomEvent) {
    this._draftPayload = { ...this._draftPayload, integrations: e.detail.integrations };
  }

  private _handleUpdateAdvanced(e: CustomEvent) {
    this._draftPayload = { ...this._draftPayload, advanced: e.detail.advanced };
  }

  private _handleUpdateMemory(e: CustomEvent) {
    this._draftPayload = { ...this._draftPayload, memory: e.detail.memory };
  }

  private _handleUpdateSecurity(e: CustomEvent) {
    this._draftPayload = { ...this._draftPayload, security: e.detail.security };
  }

  private _handleUpdateChannels(e: CustomEvent) {
    this._draftPayload = { ...this._draftPayload, channels: e.detail.channels };
  }

  private _handleUpdateIdentity(e: CustomEvent) {
    this._draftPayload = { ...this._draftPayload, identity: e.detail.identity };
  }

  render() {
    const isWide = ['connections', 'routes', 'persona-routing', 'review'].includes(this._currentStepId);
    const draftReady = Boolean(this._draftId);

    return html`
      <main class="wizard-card ${isWide ? 'wide' : ''}" role="main" aria-label="Amsha Agent Infrastructure Onboarding">
        <div class="sr-only" aria-live="polite" id="step-announcer">Step: ${this._currentStepId}</div>
        <div class="wizard-header">
          <div class="brand-mark">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
              <path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5" />
            </svg>
          </div>
          <h1 class="wizard-title">Amsha Agent</h1>
          <p class="wizard-subtitle">AI Agent Infrastructure Onboarding</p>
        </div>

        ${this._planStages.length > 0
          ? html`
              <arc-onboarding-plan-stepper
                .planStages="${this._planStages}"
                .currentStepId="${this._currentStepId}"
              ></arc-onboarding-plan-stepper>
            `
          : ''}

      <div class="wizard-body">
        ${this._vaultPriority === false && this._currentStepId !== 'review'
          ? html`
              <div class="status-msg" style="color:var(--text-warning);margin-bottom:var(--space-3)">
                Vault priority is disabled. Connections selected here will not replace environment configuration
                unless AMSHA_LLM_VAULT_PRIORITY=true.
              </div>
            `
          : ''}
        ${this._currentStepId === 'review' && this._vaultPriority === false
          ? html`
              <div class="status-msg" style="color:var(--text-warning);margin-bottom:var(--space-3)">
                Review warning: this UI-selected LLM will be stored for onboarding, but the running agent will continue using environment configuration until vault priority is enabled and the process is restarted.
              </div>
            `
          : ''}
        ${this._currentStepId === 'review' && this._effectiveSource
          ? html`
              <div class="status-msg" style="margin-bottom:var(--space-3)">
                Active configuration source: ${this._effectiveSource}. Credential state: ${this._credentialState || 'unknown'}. Credential source: ${this._credentialSource || 'unknown'}.
              </div>
            `
          : ''}
        ${this._error
          ? html`
              <div class="status-msg error" role="alert">${this._error}</div>
              ${this._bootstrapState === 'CLAIMED'
                ? html`
                    <div class="bootstrap-recovery">
                      <p>
                        This installation already has an owner. Clearing browser state cannot replace that
                        owner; use the owner API key or retry the existing browser cookie.
                      </p>
                      <input
                        type="password"
                        autocomplete="current-password"
                        placeholder="Owner API key"
                        .value="${this._ownerApiKey}"
                        @input="${(event: InputEvent) =>
                          (this._ownerApiKey = (event.target as HTMLInputElement).value)}"
                      />
                      <div class="bootstrap-recovery-actions">
                        <button class="btn-primary" @click="${this._signInOwner}" ?disabled="${this._loading}">
                          Sign In With Owner Key
                        </button>
                        <button class="btn-primary" @click="${this._recoverLocalOwner}" ?disabled="${this._loading}">
                          Recover Local Owner Session
                        </button>
                      </div>
                    </div>
                  `
                : this._bootstrapState === 'CLAIMING'
                  ? html`
                      <div class="bootstrap-recovery">
                        <div class="bootstrap-recovery-actions">
                          <button class="btn-primary" @click="${this._initDraft}" ?disabled="${this._loading}">
                            Retry Bootstrap
                          </button>
                        </div>
                      </div>
                    `
                  : ''}
            `
          : ''}
          ${this._loading ? html`<div class="status-msg loading"><div class="spinner"></div> Loading...</div>` : ''}
          
          <div style="margin-top: var(--space-4);">
            ${draftReady && !this._loading && !this._error ? this._renderStepContent() : ''}
          </div>
        </div>

        ${draftReady && !this._loading && !this._error
          ? html`
              <div class="wizard-footer">
                <button class="mode-toggle" @click="${() => (this._isAdvanced = !this._isAdvanced)}">
                  ${this._isAdvanced ? 'Switch to Quick Start' : 'Enable Advanced Setup'}
                </button>
                ${this._currentStepId !== 'welcome' && this._currentStepId !== 'complete'
                  ? html`
                      <button class="mode-toggle" style="color: var(--error);" @click="${this._discard}">
                        Discard & Restart
                      </button>
                    `
                  : ''}
              </div>
            `
          : ''}
      </main>
    `;
  }

  private _renderStepContent() {
    switch (this._currentStepId) {
      case 'welcome':
        return html`
          <arc-onboarding-step-welcome
            @quick-start="${this._handleQuickStart}"
            @advanced-start="${this._handleAdvancedStart}"
          ></arc-onboarding-step-welcome>
        `;
      case 'connections':
        return html`
          <arc-onboarding-step-connections
            .connections="${this._draftPayload.connections || []}"
            .catalog="${this._catalog}"
            .modelDefaults="${this._modelDefaults}"
            @update-connections="${this._handleUpdateConnections}"
            @next="${this._navigateNext}"
          ></arc-onboarding-step-connections>
        `;
      case 'routes':
        return html`
          <arc-onboarding-step-concern-routing
            .connections="${this._draftPayload.connections || []}"
            .catalog="${this._catalog}"
            .concernRoutes="${this._draftPayload.concernRoutes}"
            @update-routes="${this._handleUpdateRoutes}"
            @next="${this._navigateNext}"
          ></arc-onboarding-step-concern-routing>
        `;
      case 'persona-routing':
        return html`
          <arc-onboarding-step-persona-routing
            .connections="${this._draftPayload.connections || []}"
            .catalog="${this._catalog}"
            .personaRoutes="${this._draftPayload.personaRoutes || {}}"
            @update-persona-routes="${this._handleUpdatePersonaRoutes}"
            @next="${this._navigateNext}"
            @back="${this._navigateBack}"
          ></arc-onboarding-step-persona-routing>
        `;
      case 'mcp':
        return html`
          <arc-onboarding-step-mcp
            .mcpServers="${this._draftPayload.mcpServers || []}"
            @update-mcp="${this._handleUpdateMcp}"
            @next="${this._navigateNext}"
            @back="${this._navigateBack}"
          ></arc-onboarding-step-mcp>
        `;
      case 'integrations':
        return html`
          <arc-onboarding-step-integrations
            .integrations="${this._draftPayload.integrations || { playwrightEnabled: false, playwrightHeaded: false, skillsjarsApprovalRequired: true }}"
            @update-integrations="${this._handleUpdateIntegrations}"
            @next="${this._navigateNext}"
            @back="${this._navigateBack}"
          ></arc-onboarding-step-integrations>
        `;
      case 'advanced-settings':
        return html`
          <arc-onboarding-step-advanced-settings
            .advanced="${this._draftPayload.advanced || {}}"
            @update-advanced="${this._handleUpdateAdvanced}"
            @next="${this._navigateNext}"
            @back="${this._navigateBack}"
          ></arc-onboarding-step-advanced-settings>
        `;
      case 'memory':
        return html`
          <arc-onboarding-step-memory
            .memory="${this._draftPayload.memory || { tieredEnabled: true, selfEvolveEnabled: false }}"
            @update-memory="${this._handleUpdateMemory}"
            @next="${this._navigateNext}"
            @back="${this._navigateBack}"
          ></arc-onboarding-step-memory>
        `;
      case 'security':
        return html`
          <arc-onboarding-step-security
            .security="${this._draftPayload.security || {}}"
            @update-security="${this._handleUpdateSecurity}"
            @next="${this._navigateNext}"
            @back="${this._navigateBack}"
          ></arc-onboarding-step-security>
        `;
      case 'channels':
        return html`
          <arc-onboarding-step-channels
            .channels="${this._draftPayload.channels || { telegramEnabled: false, webEnabled: true, webRequireAuth: true, phoenixEnabled: false }}"
            @update-channels="${this._handleUpdateChannels}"
            @next="${this._navigateNext}"
            @back="${this._navigateBack}"
          ></arc-onboarding-step-channels>
        `;
      case 'identity':
        return html`
          <arc-onboarding-step-identity
            .identity="${this._draftPayload.identity || {}}"
            @update-identity="${this._handleUpdateIdentity}"
            @next="${this._navigateNext}"
            @back="${this._navigateBack}"
          ></arc-onboarding-step-identity>
        `;
      case 'review':
        return html`
          <arc-onboarding-step-review
            .connections="${this._draftPayload.connections || []}"
            .catalog="${this._catalog}"
            .concernRoutes="${this._draftPayload.concernRoutes}"
            .mcpServers="${this._draftPayload.mcpServers || []}"
            @finish="${this._commit}"
          ></arc-onboarding-step-review>
        `;
      case 'complete':
        return html`
          <div class="step-container" style="text-align: center; padding: var(--space-8) var(--space-4);">
            <div style="font-size: 48px; margin-bottom: var(--space-4); color: var(--color-success, #10b981);">✓</div>
            <h2 class="step-title">Configuration Complete</h2>
            <p class="step-description">Your AI Agent settings have been reviewed and are ready to commit.</p>
            <div style="margin-top: var(--space-6);">
              <button class="btn-primary" @click="${this._commit}">Get Started</button>
            </div>
          </div>
        `;
      default:
        return html`
          <div style="text-align: center; color: var(--text-muted); padding: var(--space-8) 0;">
            <p>Stage: <strong>${this._currentStepId}</strong></p>
            <p style="margin-top: var(--space-4);">
              <button class="btn-ghost" @click="${this._navigateBack}">Back</button>
              <button class="btn-primary" @click="${this._navigateNext}">Continue</button>
            </p>
          </div>
        `;
    }
  }
}
