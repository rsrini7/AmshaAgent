import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ArcOnboarding } from './arc-onboarding';
import { ArcOnboardingStepConnections } from './onboarding/arc-onboarding-step-connections';
import { ArcOnboardingStepReview } from './onboarding/arc-onboarding-step-review';
import { ArcOnboardingStepWelcome } from './onboarding/arc-onboarding-step-welcome';
import { ArcOnboardingStepMcp } from './onboarding/arc-onboarding-step-mcp';
import { ArcOnboardingStepConcernRouting } from './onboarding/arc-onboarding-step-concern-routing';
import { ArcOnboardingStepPersonaRouting } from './onboarding/arc-onboarding-step-persona-routing';
import { ArcOnboardingStepIntegrations } from './onboarding/arc-onboarding-step-integrations';
import { ArcOnboardingStepMemory } from './onboarding/arc-onboarding-step-memory';
import { ArcOnboardingStepSecurity } from './onboarding/arc-onboarding-step-security';
import { ArcOnboardingStepChannels } from './onboarding/arc-onboarding-step-channels';
import { ArcOnboardingStepIdentity } from './onboarding/arc-onboarding-step-identity';
import { ArcOnboardingStepAdvancedSettings } from './onboarding/arc-onboarding-step-advanced-settings';
import { ArcOnboardingDescriptorRenderer } from './onboarding/arc-onboarding-descriptor-renderer';
import { ArcClient, ArcApiError } from '../api/client';
import {
  ConnectionDraft,
  OnboardingStepDescriptorDto,
  ProviderDescriptor,
  O1DraftPayload,
} from '../api/types';

class MockStorage implements Storage {
  private store = new Map<string, string>();
  get length() {
    return this.store.size;
  }
  clear() {
    this.store.clear();
  }
  getItem(key: string) {
    return this.store.get(key) ?? null;
  }
  key(index: number) {
    return Array.from(this.store.keys())[index] ?? null;
  }
  removeItem(key: string) {
    this.store.delete(key);
  }
  setItem(key: string, val: string) {
    this.store.set(key, val);
  }
}

function flattenLit(template: any): string {
  if (template === null || template === undefined) return '';
  if (typeof template === 'string') return template;
  if (typeof template === 'number' || typeof template === 'boolean') return String(template);
  if (Array.isArray(template)) return template.map(flattenLit).join(' ');
  if (template.strings && Array.isArray(template.values)) {
    let result = '';
    for (let i = 0; i < template.strings.length; i++) {
      result += template.strings[i];
      if (i < template.values.length) {
        result += flattenLit(template.values[i]);
      }
    }
    return result;
  }
  return JSON.stringify(template);
}

function getHeader(headers: any, name: string): string | null {
  if (!headers) return null;
  if (typeof headers.get === 'function') {
    return headers.get(name);
  }
  const lower = name.toLowerCase();
  for (const key of Object.keys(headers)) {
    if (key.toLowerCase() === lower) {
      return headers[key];
    }
  }
  return null;
}

function mockResponse(status: number, data: any) {
  const body = typeof data === 'string' ? data : JSON.stringify(data);
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => body,
    json: async () => (body ? JSON.parse(body) : {}),
  };
}

const mockCatalog: ProviderDescriptor[] = [
  {
    id: 'groq',
    displayName: 'Groq Cloud',
    i18nKey: 'provider.groq',
    authScheme: 'BEARER',
    defaultBaseUrl: 'https://api.groq.com/openai/v1',
    requiresApiKey: true,
    embeddingCapable: false,
    modelCatalog: [{ id: 'llama-3.3-70b-versatile', label: 'Llama 3.3 70B' }],
    attributes: { color: '#f55036', icon: '⚡', tag: 'Fast Inference' },
  },
  {
    id: 'ollama',
    displayName: 'Ollama (Local)',
    i18nKey: 'provider.ollama',
    authScheme: 'NONE',
    defaultBaseUrl: 'http://localhost:11434',
    requiresApiKey: false,
    embeddingCapable: true,
    modelCatalog: [
      { id: 'llama3', label: 'Llama 3 8B' },
      { id: 'nomic-embed-text', label: 'Nomic Embed Text' },
    ],
    attributes: { color: '#111827', icon: '🦙', tag: 'Local / Open-Source' },
  },
  {
    id: 'generic-openai',
    displayName: 'Generic OpenAI Compatible',
    i18nKey: 'provider.generic-openai',
    authScheme: 'BEARER',
    defaultBaseUrl: null,
    requiresApiKey: true,
    embeddingCapable: false,
    modelCatalog: [{ id: 'custom-model', label: 'Custom Model' }],
    attributes: { color: '#10a37f', icon: '🌐', tag: 'Custom Endpoint', hasBaseUrl: 'true' },
  },
];

const mockModelDefaults = {
  groq: { defaultModel: 'llama-3.3-70b-versatile' },
  ollama: { defaultModel: 'llama3' },
};

const mockStepDescriptors: OnboardingStepDescriptorDto[] = [
  {
    id: 'connections',
    version: '1.0',
    order: 1,
    optional: false,
    title: 'LLM Connections',
    description: 'Configure external LLM providers and model endpoints.',
    fields: [
      {
        name: 'provider',
        type: 'SELECT',
        required: true,
        options: ['groq', 'ollama', 'generic-openai'],
        title: 'Provider',
        description: 'Select provider backend',
      },
      {
        name: 'modelId',
        type: 'TEXT',
        required: true,
        options: [],
        title: 'Model Identifier',
        description: 'Target model ID for completions',
      },
      {
        name: 'apiKey',
        type: 'SECRET',
        required: false,
        options: [],
        title: 'API Key',
        description: 'Authentication secret for provider API',
      },
      {
        name: 'targetUrl',
        type: 'URL',
        required: false,
        options: [],
        title: 'Target Endpoint URL',
        description: 'HTTP(S) address for completion proxy',
      },
      {
        name: 'contextWindow',
        type: 'NUMBER',
        required: true,
        options: [],
        title: 'Context Window Size',
        description: 'Maximum token context length',
      },
    ],
  },
  {
    id: 'mcp',
    version: '1.0',
    order: 4,
    optional: true,
    title: 'Model Context Protocol (MCP)',
    description: 'Connect external tool servers to extend agent capabilities.',
    fields: [
      {
        name: 'name',
        type: 'TEXT',
        required: true,
        options: [],
        title: 'Server Name',
        description: 'Unique identifier for the MCP server',
      },
      {
        name: 'endpoint',
        type: 'URL',
        required: true,
        options: [],
        title: 'Server Endpoint URL',
        description: 'HTTP or SSE endpoint',
      },
      {
        name: 'apiKey',
        type: 'SECRET',
        required: false,
        options: [],
        title: 'API Key',
        description: 'Bearer authentication token',
      },
      {
        name: 'category',
        type: 'SELECT',
        required: true,
        options: ['INTERNAL', 'EXTERNAL', 'SANDBOX'],
        title: 'Tool Category',
        description: 'Category classification',
      },
      {
        name: 'active',
        type: 'CHECKBOX',
        required: false,
        options: [],
        title: 'Enable on startup',
        description: 'Whether to activate immediately',
      },
    ],
  },
  {
    id: 'integrations',
    version: '1.0',
    order: 5,
    optional: true,
    title: 'Integrations',
    description: 'Configure auxiliary tool engines and browser automation.',
    fields: [
      {
        name: 'playwrightEnabled',
        type: 'CHECKBOX',
        required: false,
        options: [],
        title: 'Enable Playwright Browser Automation',
        description: 'Allow headless browser automation tool calls',
      },
      {
        name: 'playwrightHeaded',
        type: 'CHECKBOX',
        required: false,
        options: [],
        title: 'Run Headed Browser Window',
        description: 'Display interactive browser GUI for debugging',
      },
      {
        name: 'skillsjarsApprovalRequired',
        type: 'CHECKBOX',
        required: false,
        options: [],
        title: 'Require HITL for SkillsJar Execution',
        description: 'Prompt operator for approval before executing dynamic skills',
      },
    ],
  },
  {
    id: 'security',
    version: '1.0',
    order: 8,
    optional: true,
    title: 'Security & Governance',
    description: 'Configure audit controls, rate limits, and egress policy.',
    fields: [
      {
        name: 'rateLimitRequestsPerMin',
        type: 'NUMBER',
        required: false,
        options: [],
        title: 'Requests Per Minute',
        description: 'Maximum incoming API requests per minute',
      },
      {
        name: 'sandboxStrategy',
        type: 'SELECT',
        required: false,
        options: ['NONE', 'PODMAN', 'PROCESS'],
        title: 'Sandbox Strategy',
        description: 'Container isolation engine for untrusted scripts',
      },
    ],
  },
];

describe('Onboarding Vitest Component/Integration E2E Suite (O1)', () => {
  let localStorageMock: MockStorage;
  let sessionStorageMock: MockStorage;
  let dispatchedEvents: CustomEvent[] = [];

  beforeEach(() => {
    localStorageMock = new MockStorage();
    sessionStorageMock = new MockStorage();
    dispatchedEvents = [];

    vi.stubGlobal('localStorage', localStorageMock);
    vi.stubGlobal('sessionStorage', sessionStorageMock);
    vi.stubGlobal('confirm', vi.fn().mockReturnValue(true));
    vi.stubGlobal('window', {
      location: { hash: '#/onboarding' },
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      setTimeout: vi.fn(),
      clearTimeout: vi.fn(),
      dispatchEvent: (e: any) => dispatchedEvents.push(e),
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function createFetchMock(options?: {
    bootstrapStatus?: 'UNCLAIMED' | 'CLAIMED' | 'CLAIMING';
    claimedApiKey?: string;
    statusCredentialsOk?: boolean;
    vaultPriority?: boolean;
    approvalRejectSelf?: boolean;
  }) {
    const bootstrapStatus = options?.bootstrapStatus ?? 'UNCLAIMED';
    const claimedApiKey = options?.claimedApiKey ?? 'amsha-test-owner-key-12345';
    let currentApiKey: string | null = null;
    let currentStep = 'welcome';
    let draftPayload: any = {
      connections: [],
      concernRoutes: { agentLoop: [], compaction: [], classifier: [], embedding: [] },
    };
    let commitCount = 0;
    let discardCount = 0;

    const fetchMock = vi.fn().mockImplementation(async (url: any, init: any = {}) => {
      const path = String(url);
      const method = (init.method || 'GET').toUpperCase();
      const authHeader = getHeader(init.headers, 'x-amsha-api-key');

      // 1. /onboarding/status
      if (path.endsWith('/onboarding/status')) {
        if (!authHeader && bootstrapStatus === 'UNCLAIMED') {
          return mockResponse(401, { error: 'Unauthorized: bootstrap required' });
        }
        if (!authHeader && bootstrapStatus === 'CLAIMED' && !options?.statusCredentialsOk) {
          return mockResponse(401, { error: 'Unauthorized: bootstrap already claimed' });
        }
        return mockResponse(200, {
          step: 1,
          stepName: currentStep,
          provider: null,
          model: null,
          credentialsOk: options?.statusCredentialsOk ?? false,
          skipOnboarding: false,
          vaultPriority: options?.vaultPriority ?? true,
          effectiveSource: 'VAULT',
          credentialState: 'CONFIGURED',
          credentialSource: 'VAULT',
        });
      }

      // 2. /onboarding/bootstrap/status
      if (path.endsWith('/onboarding/bootstrap/status')) {
        return mockResponse(200, { status: bootstrapStatus });
      }

      // 3. /onboarding/bootstrap/claim
      if (path.endsWith('/onboarding/bootstrap/claim')) {
        currentApiKey = claimedApiKey;
        return mockResponse(200, { apiKey: claimedApiKey, claimedAt: '2026-09-08T07:00:00Z' });
      }

      // 4. /onboarding/bootstrap/local-recovery
      if (path.endsWith('/onboarding/bootstrap/local-recovery')) {
        currentApiKey = 'amsha-recovered-key-999';
        return mockResponse(200, { apiKey: currentApiKey });
      }

      // 5. /onboarding/provider-catalog
      if (path.endsWith('/onboarding/provider-catalog')) {
        return mockResponse(200, mockCatalog);
      }

      // 6. /model-defaults
      if (path.endsWith('/model-defaults')) {
        return mockResponse(200, mockModelDefaults);
      }

      // 7. /onboarding/drafts (create or resume)
      if (path.endsWith('/onboarding/drafts') && method === 'POST') {
        return mockResponse(200, {
          draftId: 'draft-e2e-1',
          currentStepId: currentStep,
          planFingerprint:
            'welcome->connections->routes->persona-routing->mcp->integrations->advanced-settings->memory->security->channels->identity->review',
          selectedNonSecretIds: [JSON.stringify(draftPayload)],
        });
      }

      // 8. /onboarding/drafts/{id}/steps/{stepId}
      const stepMatch = path.match(/\/onboarding\/drafts\/([^/]+)\/steps\/([^/]+)/);
      if (stepMatch && method === 'PUT') {
        const targetStep = stepMatch[2];
        currentStep = targetStep;
        if (init.body) {
          const body = JSON.parse(init.body);
          if (body.selectedNonSecretIds?.[0]) {
            draftPayload = JSON.parse(body.selectedNonSecretIds[0]);
          }
        }
        return mockResponse(200, {
          draftId: 'draft-e2e-1',
          currentStepId: targetStep,
          planFingerprint:
            'welcome->connections->routes->persona-routing->mcp->integrations->advanced-settings->memory->security->channels->identity->review',
          selectedNonSecretIds: [JSON.stringify(draftPayload)],
        });
      }

      // 9. /onboarding/drafts/{id}/validate-connection
      if (path.includes('/validate-connection') && method === 'POST') {
        const body = init.body ? JSON.parse(init.body) : {};
        if (body.probeNetwork && body.requestApproval) {
          return mockResponse(200, {
            tier: 'CONNECTION_PROBE',
            status: 'WAITING_APPROVAL',
            approvalId: 'appr-probe-100',
            valid: false,
            summary: 'Sensitive outbound network egress probe requires operator approval.',
          });
        }
        return mockResponse(200, {
          tier: 'FORMAT_VALID',
          status: 'OK',
          valid: true,
          summary: 'Connection format and schema parameters verified.',
        });
      }

      // 10. /onboarding/drafts/{id}/approvals/{approvalId}/resolve
      if (path.includes('/approvals/') && path.endsWith('/resolve') && method === 'POST') {
        if (!authHeader) {
          return mockResponse(401, { error: 'Unauthorized' });
        }
        if (options?.approvalRejectSelf && authHeader === claimedApiKey) {
          return mockResponse(403, { error: 'Forbidden: owner self-approval is rejected' });
        }
        const body = init.body ? JSON.parse(init.body) : {};
        return mockResponse(200, {
          approvalId: 'appr-probe-100',
          status: body.approved ? 'APPROVED' : 'REJECTED',
          verified: Boolean(body.approved),
          message: body.approved ? 'Connection approved and verified' : 'Approval denied',
        });
      }

      // 11. /onboarding/steps/descriptors
      if (path.endsWith('/onboarding/steps/descriptors')) {
        return mockResponse(200, mockStepDescriptors);
      }

      // 12. /onboarding/steps/{stepId}/descriptor
      const descMatch = path.match(/\/onboarding\/steps\/([^/]+)\/descriptor/);
      if (descMatch) {
        const targetId = descMatch[1];
        const desc = mockStepDescriptors.find((d) => d.id === targetId);
        if (!desc) {
          return mockResponse(404, { error: 'Descriptor not found' });
        }
        return mockResponse(200, desc);
      }

      // 13. /onboarding/drafts/{id}/commit
      if (path.endsWith('/commit') && method === 'POST') {
        commitCount++;
        return mockResponse(200, {
          status: 'COMPLETED',
          draftId: 'draft-e2e-1',
          committedAt: '2026-09-08T07:10:00Z',
        });
      }

      // 14. /onboarding/drafts/discard
      if (path.endsWith('/onboarding/drafts/discard') && method === 'POST') {
        discardCount++;
        currentStep = 'welcome';
        draftPayload = {
          connections: [],
          concernRoutes: { agentLoop: [], compaction: [], classifier: [], embedding: [] },
        };
        return mockResponse(200, { status: 'DISCARDED' });
      }

      return mockResponse(200, {});
    });

    return {
      fetchMock,
      getCommitCount: () => commitCount,
      getDiscardCount: () => discardCount,
      getCurrentApiKey: () => currentApiKey,
    };
  }

  describe('Suite 1: Bootstrap Claim, Session Initialization & Local Recovery E2E', () => {
    it('claims first-owner API key on fresh UNCLAIMED install, stores in localStorage, and starts draft', async () => {
      const { fetchMock } = createFetchMock({ bootstrapStatus: 'UNCLAIMED' });
      vi.stubGlobal('fetch', fetchMock);

      const wizard = new ArcOnboarding();
      await (wizard as any)._initDraft();

      // Proves: Persisted key saved in localStorage
      expect(localStorageMock.getItem('amsha-owner-api-key')).toBe('amsha-test-owner-key-12345');
      // Proves: Draft created and loaded
      expect((wizard as any)._draftId).toBe('draft-e2e-1');
      expect((wizard as any)._currentStepId).toBe('welcome');
      expect((wizard as any)._catalog).toHaveLength(3);
      expect((wizard as any)._modelDefaults).toHaveProperty('groq');

      // Proves: Rendered UI displays welcome step
      const rendered = flattenLit(wizard.render());
      expect(rendered).toContain('Amsha Agent');
      expect(rendered).toContain('AI Agent Infrastructure Onboarding');
      expect(rendered).toContain('Step: welcome');
    });

    it('surfaces retry UI when bootstrap status is CLAIMING', async () => {
      const { fetchMock } = createFetchMock({ bootstrapStatus: 'CLAIMING' });
      vi.stubGlobal('fetch', fetchMock);

      const wizard = new ArcOnboarding();
      await (wizard as any)._initDraft();

      expect((wizard as any)._bootstrapState).toBe('CLAIMING');
      expect((wizard as any)._error).toContain('Bootstrap ownership is currently being claimed');

      const rendered = flattenLit(wizard.render());
      expect(rendered).toContain('Retry Bootstrap');
    });

    it('displays owner key sign-in recovery form when bootstrap is CLAIMED and unauthenticated', async () => {
      const { fetchMock } = createFetchMock({ bootstrapStatus: 'CLAIMED' });
      vi.stubGlobal('fetch', fetchMock);

      const wizard = new ArcOnboarding();
      await (wizard as any)._initDraft();

      expect((wizard as any)._bootstrapState).toBe('CLAIMED');
      expect((wizard as any)._error).toContain('Bootstrap is already claimed');

      const rendered = flattenLit(wizard.render());
      expect(rendered).toContain('Sign In With Owner Key');
      expect(rendered).toContain('Recover Local Owner Session');

      // Submitting owner key recovers draft
      (wizard as any)._ownerApiKey = 'owner-supplied-secret-key';
      await (wizard as any)._signInOwner();

      expect(localStorageMock.getItem('amsha-owner-api-key')).toBe('owner-supplied-secret-key');
      expect((wizard as any)._draftId).toBe('draft-e2e-1');
    });

    it('recovers local owner session via /recover-local-owner endpoint', async () => {
      const { fetchMock } = createFetchMock({ bootstrapStatus: 'CLAIMED' });
      vi.stubGlobal('fetch', fetchMock);

      const wizard = new ArcOnboarding();
      (wizard as any)._bootstrapState = 'CLAIMED';
      await (wizard as any)._recoverLocalOwner();

      expect(localStorageMock.getItem('amsha-owner-api-key')).toBe('amsha-recovered-key-999');
      expect((wizard as any)._draftId).toBe('draft-e2e-1');
      expect((wizard as any)._error).toBe('');
    });

    it('dispatches api-key-set event immediately when credentials are already configured', async () => {
      const { fetchMock } = createFetchMock({ statusCredentialsOk: true });
      vi.stubGlobal('fetch', fetchMock);
      localStorageMock.setItem('amsha-owner-api-key', 'already-configured-key');

      const wizard = new ArcOnboarding();
      let eventFired = false;
      wizard.addEventListener('api-key-set', ((e: CustomEvent) => {
        eventFired = true;
        expect(e.detail.key).toBe('already-configured-key');
      }) as EventListener);

      await (wizard as any)._initDraft();
      expect(eventFired).toBe(true);
    });
  });

  describe('Suite 2: Schema-Driven Descriptors, Localized i18n & Form Validation E2E', () => {
    it('fetches all typed step descriptors and single step descriptor via ArcClient', async () => {
      const { fetchMock } = createFetchMock();
      vi.stubGlobal('fetch', fetchMock);

      const client = new ArcClient('/api/v1', 'test-key');
      const allDescriptors = await client.getStepDescriptors();
      expect(allDescriptors).toHaveLength(4);
      expect(allDescriptors.map((d: any) => d.id)).toEqual([
        'connections',
        'mcp',
        'integrations',
        'security',
      ]);

      const mcpDesc = await client.getStepDescriptor('mcp');
      expect(mcpDesc.id).toBe('mcp');
      expect(mcpDesc.fields).toHaveLength(5);
    });

    it('renders schema-driven form inputs for TEXT, NUMBER, URL, SECRET, SELECT, and CHECKBOX with i18n copy', () => {
      const renderer = new ArcOnboardingDescriptorRenderer();
      const mcpDesc = mockStepDescriptors.find((d) => d.id === 'mcp')!;

      renderer.descriptor = mcpDesc;
      renderer.values = {
        name: 'brave-search',
        endpoint: 'https://brave.internal/mcp',
        apiKey: 'token-secret',
        category: 'INTERNAL',
        active: true,
      };

      const rendered = flattenLit(renderer.render());

      // Landmark and titles
      expect(rendered).toContain('role="form"');
      expect(rendered).toContain('id="step-heading-mcp"');
      expect(rendered).toContain('Model Context Protocol (MCP)');
      expect(rendered).toContain('Connect external tool servers to extend agent capabilities.');

      // Field labels & descriptions
      expect(rendered).toContain('Server Name');
      expect(rendered).toContain('Unique identifier for the MCP server');
      expect(rendered).toContain('Server Endpoint URL');
      expect(rendered).toContain('HTTP or SSE endpoint');
      expect(rendered).toContain('Tool Category');
      expect(rendered).toContain('Enable on startup');

      // Proper HTML input types
      expect(rendered).toContain('type="url"');
      expect(rendered).toContain('type="password"');
      expect(rendered).toContain('autocomplete="new-password"');
      expect(rendered).toContain('type="checkbox"');
      expect(rendered).toContain('<option value="INTERNAL"');
      expect(rendered).toContain('<option value="EXTERNAL"');
    });

    it('displays validation errors with role="alert" and aria-invalid="true"', () => {
      const renderer = new ArcOnboardingDescriptorRenderer();
      const mcpDesc = mockStepDescriptors.find((d) => d.id === 'mcp')!;

      renderer.descriptor = mcpDesc;
      renderer.values = { name: '', endpoint: 'not-a-url' };
      renderer.errors = {
        name: 'Server Name is required',
        endpoint: 'Must be a valid HTTP(S) URL',
      };

      const rendered = flattenLit(renderer.render());

      expect(rendered).toContain('role="alert"');
      expect(rendered).toContain('Server Name is required');
      expect(rendered).toContain('Must be a valid HTTP(S) URL');
      expect(rendered).toContain('aria-invalid="true"');
      expect(rendered).toContain('aria-describedby="desc-name err-name"');
    });

    it('dispatches update-values, next, and back events from descriptor renderer', () => {
      const renderer = new ArcOnboardingDescriptorRenderer();
      renderer.descriptor = mockStepDescriptors[0];
      renderer.values = { provider: 'groq' };

      let updatedValues: any = null;
      let nextFired = false;
      let backFired = false;

      renderer.addEventListener('update-values', ((e: CustomEvent) => {
        updatedValues = e.detail;
      }) as EventListener);
      renderer.addEventListener('next', () => {
        nextFired = true;
      });
      renderer.addEventListener('back', () => {
        backFired = true;
      });

      (renderer as any)._handleInput('modelId', 'llama-3.3-70b-versatile');
      expect(updatedValues).toEqual({
        fieldName: 'modelId',
        value: 'llama-3.3-70b-versatile',
        values: { provider: 'groq', modelId: 'llama-3.3-70b-versatile' },
      });

      (renderer as any)._next();
      expect(nextFired).toBe(true);

      (renderer as any)._back();
      expect(backFired).toBe(true);
    });
  });

  describe('Suite 3: Connection Configuration, Tiered Validation & Sensitive HITL Approval E2E', () => {
    it('validates provider constraints: enforces required API key on Groq and valid URL on generic-openai', () => {
      const step = new ArcOnboardingStepConnections();
      step.catalog = mockCatalog;
      step.modelDefaults = mockModelDefaults;

      // 1. Groq selected by default (requires API key)
      (step as any)._selectedProviderId = 'groq';
      (step as any)._apiKey = '';
      expect((step as any)._connectionValidationMessage()).toBe(
        'An API key is required for this provider.'
      );

      // Provide API key -> valid
      (step as any)._apiKey = 'gsk-test12345';
      expect((step as any)._connectionValidationMessage()).toBe('');

      // 2. Generic OpenAI selected (requires valid targetUrl)
      (step as any)._selectedProviderId = 'generic-openai';
      (step as any)._targetUrl = 'invalid-url';
      expect((step as any)._connectionValidationMessage()).toBe(
        'Generic OpenAI requires a valid HTTP(S) target URL.'
      );

      (step as any)._targetUrl = 'https://custom-ai.company.com/v1';
      expect((step as any)._connectionValidationMessage()).toBe('');
    });

    it('adds valid connection profile and emits update-connections event', () => {
      const step = new ArcOnboardingStepConnections();
      step.catalog = mockCatalog;
      step.modelDefaults = mockModelDefaults;
      step.connections = [];

      let emittedConnections: ConnectionDraft[] = [];
      step.addEventListener('update-connections', ((e: CustomEvent) => {
        emittedConnections = e.detail.connections;
      }) as EventListener);

      (step as any)._selectedProviderId = 'groq';
      (step as any)._modelId = 'llama-3.3-70b-versatile';
      (step as any)._apiKey = 'gsk-secret-key';
      (step as any)._contextWindow = 32768;
      (step as any)._capabilities = ['tool-calling', 'vision'];

      (step as any)._addConnection();

      expect(emittedConnections).toHaveLength(1);
      expect(emittedConnections[0]).toMatchObject({
        provider: 'groq',
        modelId: 'llama-3.3-70b-versatile',
        apiKey: 'gsk-secret-key',
        contextWindow: 32768,
        capabilities: ['tool-calling', 'vision'],
      });
      expect(emittedConnections[0].apiKeyVaultRef).toBeDefined();
    });

    it('executes format-tier validation and returns FORMAT_VALID', async () => {
      const { fetchMock } = createFetchMock();
      vi.stubGlobal('fetch', fetchMock);

      const client = new ArcClient('/api/v1', 'test-key');
      const res = await client.validateDraftConnection('draft-e2e-1', {
        connectionIndex: 0,
        probeNetwork: false,
        requestApproval: false,
      });

      expect(res.tier).toBe('FORMAT_VALID');
      expect(res.status).toBe('OK');
      expect(res.valid).toBe(true);
    });

    it('requests sensitive outbound probe, enters WAITING_APPROVAL, and rejects draft owner self-approval (403)', async () => {
      const { fetchMock } = createFetchMock({
        claimedApiKey: 'owner-key-1',
        approvalRejectSelf: true,
      });
      vi.stubGlobal('fetch', fetchMock);

      const ownerClient = new ArcClient('/api/v1', 'owner-key-1');

      // Step 1: Probe request triggers HITL approval requirement
      const probeRes = await ownerClient.validateDraftConnection('draft-e2e-1', {
        connectionIndex: 0,
        probeNetwork: true,
        requestApproval: true,
      });

      expect(probeRes.tier).toBe('CONNECTION_PROBE');
      expect(probeRes.status).toBe('WAITING_APPROVAL');
      expect(probeRes.approvalId).toBe('appr-probe-100');

      // Step 2: Owner attempting self-approval is rejected with 403 Forbidden
      await expect(
        ownerClient.resolveDraftApproval('draft-e2e-1', probeRes.approvalId, true, 'Self approval')
      ).rejects.toThrow();
    });

    it('allows distinct operator to resolve approval with verified: true', async () => {
      const { fetchMock } = createFetchMock({
        claimedApiKey: 'owner-key-1',
        approvalRejectSelf: true,
      });
      vi.stubGlobal('fetch', fetchMock);

      const operatorClient = new ArcClient('/api/v1', 'operator-internal-key');
      const resolveRes = await operatorClient.resolveDraftApproval(
        'draft-e2e-1',
        'appr-probe-100',
        true,
        'Operator verified network endpoint'
      );

      expect(resolveRes.status).toBe('APPROVED');
      expect(resolveRes.verified).toBe(true);
      expect(resolveRes.message).toContain('approved and verified');
    });
  });

  describe('Suite 4: Quick-Start Workflow End-to-End', () => {
    it('executes full Quick Start pathway: selects keyless Ollama, seeds routes, advances to review, and commits', async () => {
      const { fetchMock, getCommitCount } = createFetchMock({ bootstrapStatus: 'UNCLAIMED' });
      vi.stubGlobal('fetch', fetchMock);

      const wizard = new ArcOnboarding();
      let keySetDetail: any = null;
      wizard.addEventListener('api-key-set', ((e: CustomEvent) => {
        keySetDetail = e.detail;
      }) as EventListener);

      // Initialize wizard
      await (wizard as any)._initDraft();
      expect((wizard as any)._currentStepId).toBe('welcome');

      // User triggers Quick Start
      (wizard as any)._handleQuickStart();
      // Allow async save and navigation to complete
      await vi.waitFor(() => {
        expect((wizard as any)._currentStepId).toBe('connections');
      });

      // Proves: Selected Ollama (keyless), seeded routes, navigated to connections
      expect((wizard as any)._draftPayload.connections).toHaveLength(1);
      expect((wizard as any)._draftPayload.connections[0].provider).toBe('ollama');
      expect((wizard as any)._draftPayload.concernRoutes.agentLoop).toEqual([0]);
      expect((wizard as any)._draftPayload.concernRoutes.embedding).toEqual([0]);

      // In Quick Start mode (!isAdvanced), navigating next jumps directly to review
      (wizard as any)._navigateNext();
      await vi.waitFor(() => {
        expect((wizard as any)._currentStepId).toBe('review');
      });

      // Review step reflects connection and route data in wizard template
      const renderedWizard = flattenLit(wizard.render());
      expect(renderedWizard).toContain('<arc-onboarding-step-review');
      expect(renderedWizard).toContain('ollama');

      // Test the review step component internal DOM rendering
      const reviewStep = new ArcOnboardingStepReview();
      reviewStep.connections = (wizard as any)._draftPayload.connections;
      reviewStep.catalog = (wizard as any)._catalog;
      reviewStep.concernRoutes = (wizard as any)._draftPayload.concernRoutes;
      const renderedReview = flattenLit(reviewStep.render());
      expect(renderedReview).toContain('Review & Finalize Onboarding');
      expect(renderedReview).toContain('Total API Connections');
      expect(renderedReview).toContain('Commit Configuration & Start Chat');

      // Committing draft
      await (wizard as any)._commit();

      expect(getCommitCount()).toBe(1);
      expect(keySetDetail).toEqual({ key: 'amsha-test-owner-key-12345' });
    });
  });

  describe('Suite 5: Advanced Multi-Stage Navigation & Lifecycle E2E', () => {
    it('navigates through all 11 stages in Advanced Setup, transforming MCP payloads and persisting stage state', async () => {
      const { fetchMock, getCommitCount } = createFetchMock({ bootstrapStatus: 'UNCLAIMED' });
      vi.stubGlobal('fetch', fetchMock);

      const wizard = new ArcOnboarding();
      await (wizard as any)._initDraft();

      // Enter Advanced Setup
      (wizard as any)._handleAdvancedStart();
      await vi.waitFor(() => {
        expect((wizard as any)._isAdvanced).toBe(true);
        expect((wizard as any)._currentStepId).toBe('connections');
      });

      // Step 1 -> Connections: Add Groq + Ollama
      (wizard as any)._handleUpdateConnections(
        new CustomEvent('update-connections', {
          detail: {
            connections: [
              {
                provider: 'groq',
                modelId: 'llama-3.3-70b-versatile',
                apiKey: 'gsk-key-1',
                contextWindow: 8192,
                structuredOutputMode: 'NATIVE',
                capabilities: ['tool-calling'],
              },
              {
                provider: 'ollama',
                modelId: 'nomic-embed-text',
                contextWindow: 4096,
                structuredOutputMode: 'NATIVE',
                capabilities: [],
              },
            ],
          },
        })
      );
      (wizard as any)._navigateNext();
      await vi.waitFor(() => {
        expect((wizard as any)._currentStepId).toBe('routes');
      });

      // Step 2 -> Concern Routing: agentLoop on 0, embedding on 1
      (wizard as any)._handleUpdateRoutes(
        new CustomEvent('update-routes', {
          detail: {
            concernRoutes: {
              agentLoop: [0],
              compaction: [0],
              classifier: [0],
              embedding: [1],
            },
          },
        })
      );
      (wizard as any)._navigateNext();
      await vi.waitFor(() => {
        expect((wizard as any)._currentStepId).toBe('persona-routing');
      });

      // Step 3 -> Persona Routing: researcher overrides
      (wizard as any)._handleUpdatePersonaRoutes(
        new CustomEvent('update-persona-routes', {
          detail: {
            personaRoutes: {
              researcher: { primary: 0, failover: [] },
            },
          },
        })
      );
      (wizard as any)._navigateNext();
      await vi.waitFor(() => {
        expect((wizard as any)._currentStepId).toBe('mcp');
      });

      // Step 4 -> MCP: configure server and verify payload transformation
      (wizard as any)._handleUpdateMcp(
        new CustomEvent('update-mcp', {
          detail: {
            mcpServers: [
              { name: 'brave-search', transport: 'sse', url: 'https://mcp.brave.internal' },
            ],
          },
        })
      );
      (wizard as any)._navigateNext();
      await vi.waitFor(() => {
        expect((wizard as any)._currentStepId).toBe('integrations');
      });

      // Step 5 -> Integrations: Playwright enabled
      (wizard as any)._handleUpdateIntegrations(
        new CustomEvent('update-integrations', {
          detail: {
            integrations: {
              playwrightEnabled: true,
              playwrightHeaded: false,
              skillsjarsApprovalRequired: true,
            },
          },
        })
      );
      (wizard as any)._navigateNext();
      await vi.waitFor(() => {
        expect((wizard as any)._currentStepId).toBe('advanced-settings');
      });

      // Step 6 -> Advanced Settings: temperature & maxRetries
      (wizard as any)._handleUpdateAdvanced(
        new CustomEvent('update-advanced', {
          detail: { advanced: { temperature: 0.2, maxRetries: 3 } },
        })
      );
      (wizard as any)._navigateNext();
      await vi.waitFor(() => {
        expect((wizard as any)._currentStepId).toBe('memory');
      });

      // Step 7 -> Memory: tiered memory & self-evolve
      (wizard as any)._handleUpdateMemory(
        new CustomEvent('update-memory', {
          detail: { memory: { tieredEnabled: true, selfEvolveEnabled: true } },
        })
      );
      (wizard as any)._navigateNext();
      await vi.waitFor(() => {
        expect((wizard as any)._currentStepId).toBe('security');
      });

      // Step 8 -> Security: rate limits
      (wizard as any)._handleUpdateSecurity(
        new CustomEvent('update-security', {
          detail: { security: { rateLimitRequestsPerMin: 60 } },
        })
      );
      (wizard as any)._navigateNext();
      await vi.waitFor(() => {
        expect((wizard as any)._currentStepId).toBe('channels');
      });

      // Step 9 -> Channels: webEnabled and telegramEnabled
      (wizard as any)._handleUpdateChannels(
        new CustomEvent('update-channels', {
          detail: {
            channels: {
              webEnabled: true,
              webRequireAuth: true,
              telegramEnabled: true,
              phoenixEnabled: false,
            },
          },
        })
      );
      (wizard as any)._navigateNext();
      await vi.waitFor(() => {
        expect((wizard as any)._currentStepId).toBe('identity');
      });

      // Step 10 -> Identity: agent metadata
      (wizard as any)._handleUpdateIdentity(
        new CustomEvent('update-identity', {
          detail: { identity: { oidcClientId: 'client-123' } },
        })
      );
      (wizard as any)._navigateNext();
      await vi.waitFor(() => {
        expect((wizard as any)._currentStepId).toBe('review');
      });

      // Step 11 -> Review and Commit
      const renderedWizard = flattenLit(wizard.render());
      expect(renderedWizard).toContain('<arc-onboarding-step-review');
      expect(renderedWizard).toContain('llama-3.3-70b-versatile');

      // Test review step component internal DOM rendering
      const reviewStep = new ArcOnboardingStepReview();
      reviewStep.connections = (wizard as any)._draftPayload.connections;
      reviewStep.catalog = (wizard as any)._catalog;
      reviewStep.concernRoutes = (wizard as any)._draftPayload.concernRoutes;
      reviewStep.mcpServers = [{ name: 'brave-search', transport: 'sse', url: 'https://mcp.brave.internal' }];
      const renderedReview = flattenLit(reviewStep.render());
      expect(renderedReview).toContain('Review & Finalize Onboarding');
      expect(renderedReview).toContain('Total API Connections');
      expect(renderedReview).toContain('Groq Cloud (llama-3.3-70b-versatile)');
      expect(renderedReview).toContain('1 configured (brave-search)');

      await (wizard as any)._commit();
      expect(getCommitCount()).toBe(1);
    });

    it('prompts confirmation, calls discard endpoint, and restarts draft on discard', async () => {
      const { fetchMock, getDiscardCount } = createFetchMock({ bootstrapStatus: 'UNCLAIMED' });
      vi.stubGlobal('fetch', fetchMock);

      const wizard = new ArcOnboarding();
      await (wizard as any)._initDraft();
      (wizard as any)._currentStepId = 'connections';

      await (wizard as any)._discard();

      expect(getDiscardCount()).toBe(1);
      expect((wizard as any)._currentStepId).toBe('welcome');
    });

    it('renders vault priority warning banner when vaultPriority is false', async () => {
      const { fetchMock } = createFetchMock({ vaultPriority: false });
      vi.stubGlobal('fetch', fetchMock);

      const wizard = new ArcOnboarding();
      await (wizard as any)._initDraft();

      // On standard step
      let rendered = flattenLit(wizard.render());
      expect(rendered).toContain('Vault priority is disabled');

      // On review step
      (wizard as any)._currentStepId = 'review';
      rendered = flattenLit(wizard.render());
      expect(rendered).toContain('Review warning: this UI-selected LLM will be stored for onboarding');
    });
  });

  describe('Suite 6: Stage Subcomponents Form Rendering & Event Handling E2E', () => {
    it('ArcOnboardingStepWelcome renders welcome content and emits quick-start and advanced-start', () => {
      const step = new ArcOnboardingStepWelcome();
      let quickCalled = false;
      let advancedCalled = false;

      step.addEventListener('quick-start', () => {
        quickCalled = true;
      });
      step.addEventListener('advanced-start', () => {
        advancedCalled = true;
      });

      const rendered = flattenLit(step.render());
      expect(rendered).toContain('Welcome to Amsha Agent');
      expect(rendered).toContain('Quick Start (Recommended)');
      expect(rendered).toContain('Advanced Multi-Model Setup');

      (step as any)._onQuickStart();
      expect(quickCalled).toBe(true);

      (step as any)._onAdvancedStart();
      expect(advancedCalled).toBe(true);
    });

    it('ArcOnboardingStepMcp renders server list, handles adding/removing servers, next, and back', () => {
      const step = new ArcOnboardingStepMcp();
      step.mcpServers = [];

      let updatedServers: any = null;
      let nextCalled = false;
      let backCalled = false;

      step.addEventListener('update-mcp', ((e: CustomEvent) => {
        updatedServers = e.detail.mcpServers;
      }) as EventListener);
      step.addEventListener('next', () => {
        nextCalled = true;
      });
      step.addEventListener('back', () => {
        backCalled = true;
      });

      // 1. Empty state
      let rendered = flattenLit(step.render());
      expect(rendered).toContain('No MCP servers added yet');

      // 2. Add MCP server with category and custom headers (masked in DOM)
      (step as any)._showAddMcp = true;
      (step as any)._headerKey = 'Authorization';
      (step as any)._headerValue = 'Bearer test-mcp-key';
      (step as any)._addHeader();
      const formRendered = flattenLit(step.render());
      expect(formRendered).toContain('••••••••');
      expect(formRendered).not.toContain('Bearer test-mcp-key');

      (step as any)._newMcp = {
        name: 'brave-search',
        transport: 'sse',
        url: 'https://mcp.brave.internal',
        category: 'NETWORK',
      };
      (step as any)._addMcp();

      expect(updatedServers).toHaveLength(1);
      expect(updatedServers[0]).toMatchObject({
        name: 'brave-search',
        transport: 'sse',
        url: 'https://mcp.brave.internal',
        category: 'NETWORK',
        headers: {
          Authorization: 'Bearer test-mcp-key',
        },
      });

      // 3. Render populated list and remove
      step.mcpServers = updatedServers;
      rendered = flattenLit(step.render());
      expect(rendered).toContain('brave-search');
      expect(rendered).toContain('NETWORK');
      expect(rendered).toContain('sse');
      expect(rendered).toContain('1 header');
      expect(rendered).toContain('mcp-remove');

      (step as any)._removeMcp(0);
      expect(updatedServers).toHaveLength(0);

      (step as any)._next();
      expect(nextCalled).toBe(true);

      (step as any)._back();
      expect(backCalled).toBe(true);
    });

    it('ArcOnboardingStepConcernRouting renders concern cards, handles route change, and emits next', () => {
      const step = new ArcOnboardingStepConcernRouting();
      step.connections = [
        {
          provider: 'groq',
          modelId: 'llama-3.3-70b-versatile',
          contextWindow: 8192,
          structuredOutputMode: 'NATIVE',
          capabilities: ['tool-calling'],
        },
      ];
      step.catalog = mockCatalog;
      step.concernRoutes = {
        agentLoop: [0],
        compaction: [0],
        classifier: [0],
        embedding: [],
      };

      let updatedRoutes: any = null;
      let nextCalled = false;
      step.addEventListener('update-routes', ((e: CustomEvent) => {
        updatedRoutes = e.detail.concernRoutes;
      }) as EventListener);
      step.addEventListener('next', () => {
        nextCalled = true;
      });

      const rendered = flattenLit(step.render());
      expect(rendered).toContain('Agent Execution Loop');
      expect(rendered).toContain('Memory Compaction');
      expect(rendered).toContain('Task Classifier');
      expect(rendered).toContain('Embedding Engine');

      (step as any)._onChange('compaction', 0);
      expect(updatedRoutes.compaction).toEqual([0]);

      (step as any)._onNext();
      expect(nextCalled).toBe(true);
    });

    it('ArcOnboardingStepPersonaRouting renders persona cards, handles route change, next, and back', () => {
      const step = new ArcOnboardingStepPersonaRouting();
      step.connections = [
        {
          provider: 'groq',
          modelId: 'llama-3.3-70b-versatile',
          contextWindow: 8192,
          structuredOutputMode: 'NATIVE',
          capabilities: ['tool-calling'],
        },
      ];
      step.catalog = mockCatalog;

      let updatedRoutes: any = null;
      let nextCalled = false;
      let backCalled = false;

      step.addEventListener('update-persona-routes', ((e: CustomEvent) => {
        updatedRoutes = e.detail.personaRoutes;
      }) as EventListener);
      step.addEventListener('next', () => {
        nextCalled = true;
      });
      step.addEventListener('back', () => {
        backCalled = true;
      });

      const rendered = flattenLit(step.render());
      expect(rendered).toContain('Persona Routing (Optional)');
      expect(rendered).toContain('Codebase Researcher');
      expect(rendered).toContain('Self Developer Agent');

      (step as any)._onChange('research', 0);
      expect(updatedRoutes.research).toEqual({ primary: 0, failover: [] });

      (step as any)._next();
      expect(nextCalled).toBe(true);

      (step as any)._back();
      expect(backCalled).toBe(true);
    });

    it('ArcOnboardingStepIntegrations renders setup guidance and supports navigation', () => {
      const step = new ArcOnboardingStepIntegrations();
      let nextCalled = false;
      let backCalled = false;

      step.addEventListener('next', () => {
        nextCalled = true;
      });
      step.addEventListener('back', () => {
        backCalled = true;
      });

      const rendered = flattenLit(step.render());
      expect(rendered).toContain('Integrations (Optional)');
      expect(rendered).toContain('Search, GitHub, and browser automation');
      expect(rendered).toContain('AMSHA_TOOLS_PLAYWRIGHT_ENABLED');

      (step as any)._next();
      expect(nextCalled).toBe(true);

      (step as any)._back();
      expect(backCalled).toBe(true);
    });

    it('ArcOnboardingStepMemory handles memory toggles, next, and back', () => {
      const step = new ArcOnboardingStepMemory();
      let updatedMemory: any = null;
      let nextCalled = false;
      let backCalled = false;

      step.addEventListener('update-memory', ((e: CustomEvent) => {
        updatedMemory = e.detail.memory;
      }) as EventListener);
      step.addEventListener('next', () => {
        nextCalled = true;
      });
      step.addEventListener('back', () => {
        backCalled = true;
      });

      const rendered = flattenLit(step.render());
      expect(rendered).toContain('Memory & Self-Evolve (Optional)');
      expect(rendered).toContain('Enable Tiered Memory Architecture');
      expect(rendered).toContain('Enable Self-Evolving Prompts');

      (step as any)._onChange('selfEvolveEnabled', true);
      expect(updatedMemory.selfEvolveEnabled).toBe(true);

      (step as any)._next();
      expect(nextCalled).toBe(true);

      (step as any)._back();
      expect(backCalled).toBe(true);
    });

    it('ArcOnboardingStepSecurity handles security configuration changes, next, and back', () => {
      const step = new ArcOnboardingStepSecurity();
      let updatedSecurity: any = null;
      let nextCalled = false;
      let backCalled = false;

      step.addEventListener('update-security', ((e: CustomEvent) => {
        updatedSecurity = e.detail.security;
      }) as EventListener);
      step.addEventListener('next', () => {
        nextCalled = true;
      });
      step.addEventListener('back', () => {
        backCalled = true;
      });

      const rendered = flattenLit(step.render());
      expect(rendered).toContain('Security & Sandbox Governance (Optional)');
      expect(rendered).toContain('Requests / Min');
      expect(rendered).toContain('Sandbox Strategy');

      (step as any)._onChange('rateLimitRequestsPerMin', 120);
      expect(updatedSecurity.rateLimitRequestsPerMin).toBe(120);

      (step as any)._next();
      expect(nextCalled).toBe(true);

      (step as any)._back();
      expect(backCalled).toBe(true);
    });

    it('ArcOnboardingStepChannels renders channel setup guidance and supports navigation', () => {
      const step = new ArcOnboardingStepChannels();
      let nextCalled = false;
      let backCalled = false;

      step.addEventListener('next', () => {
        nextCalled = true;
      });
      step.addEventListener('back', () => {
        backCalled = true;
      });

      const rendered = flattenLit(step.render());
      expect(rendered).toContain('Channels (Optional)');
      expect(rendered).toContain('AMSHA_TELEGRAM_ALLOWED_USER_IDS');
      expect(rendered).toContain('AMSHA_TELEGRAM_ALLOWED_USERS');
      expect(rendered).toContain('Web chat and schedules');

      (step as any)._next();
      expect(nextCalled).toBe(true);

      (step as any)._back();
      expect(backCalled).toBe(true);
    });

    it('ArcOnboardingStepIdentity renders identity setup guidance and supports navigation', () => {
      const step = new ArcOnboardingStepIdentity();
      let nextCalled = false;
      let backCalled = false;

      step.addEventListener('next', () => {
        nextCalled = true;
      });
      step.addEventListener('back', () => {
        backCalled = true;
      });

      const rendered = flattenLit(step.render());
      expect(rendered).toContain('External Identity (Optional)');
      expect(rendered).toContain('Generic OIDC');
      expect(rendered).toContain('AMSHA_AUTH_IDP_OIDC_CLIENT_ID');

      (step as any)._next();
      expect(nextCalled).toBe(true);

      (step as any)._back();
      expect(backCalled).toBe(true);
    });

    it('ArcOnboardingStepAdvancedSettings handles temperature updates, next, and back', () => {
      const step = new ArcOnboardingStepAdvancedSettings();
      let updatedAdvanced: any = null;
      let nextCalled = false;
      let backCalled = false;

      step.addEventListener('update-advanced', ((e: CustomEvent) => {
        updatedAdvanced = e.detail.advanced;
      }) as EventListener);
      step.addEventListener('next', () => {
        nextCalled = true;
      });
      step.addEventListener('back', () => {
        backCalled = true;
      });

      const rendered = flattenLit(step.render());
      expect(rendered).toContain('Advanced Settings (Optional)');
      expect(rendered).toContain('LLM Temperature');
      expect(rendered).toContain('Max Retries');

      (step as any)._onChange('temperature', 0.5);
      expect(updatedAdvanced.temperature).toBe(0.5);

      (step as any)._next();
      expect(nextCalled).toBe(true);

      (step as any)._back();
      expect(backCalled).toBe(true);
    });
  });
});
