import { LitElement } from 'lit';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ArcClient } from '../api/client';
import type { SettingsDto } from '../api/types';
import { ArcSettings } from './arc-settings';

function flattenLit(value: unknown): string {
  if (typeof value === 'string' || typeof value === 'number') return String(value);
  if (Array.isArray(value)) return value.map(flattenLit).join('');
  if (value && typeof value === 'object' && 'strings' in value && 'values' in value) {
    const template = value as { strings: readonly string[]; values: unknown[] };
    return template.strings.reduce(
      (result, part, index) => result + part + (index < template.values.length ? flattenLit(template.values[index]) : ''),
      '',
    );
  }
  return '';
}

const settings: SettingsDto = {
  provider: 'openrouter',
  model: 'auto',
  systemPrompt: 'Answer clearly.',
  hasApiKey: true,
  appVersion: '0.1.0',
  skipOnboarding: false,
  vaultPriority: true,
  effectiveSource: 'VAULT',
  credentialState: 'CONFIGURED',
  credentialSource: 'VAULT',
  voice: 'serena',
  speechOutputMode: 'TEXT_AND_AUDIO',
  availableVoices: ['default', 'serena'],
};

function component(): ArcSettings {
  const result = new ArcSettings();
  (result as any)._appContext = { apiKey: 'test-api-key' };
  return result;
}

describe('ArcSettings', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('loads provider, prompt, voice, and network settings for the connected component', async () => {
    vi.spyOn(LitElement.prototype, 'connectedCallback').mockImplementation(() => {});
    vi.spyOn(ArcClient.prototype, 'getSettings').mockResolvedValue(settings);
    vi.spyOn(ArcClient.prototype, 'getPrompt').mockResolvedValue({ systemPrompt: 'Prompt from server' });
    vi.spyOn(ArcClient.prototype, 'getConnectionNetworkProfile').mockResolvedValue({
      egressMode: 'ALLOW_LIST',
      allowedHosts: ['api.example.test'],
      requireProxy: true,
      proxyUrl: 'https://proxy.example.test',
      noProxyHosts: ['localhost'],
      connectTimeoutMs: 1000,
      readTimeoutMs: 2000,
      tlsTrustMode: 'CUSTOM_CA',
      customCaVaultRef: 'vault:ca',
    });
    const settingsComponent = component();

    await settingsComponent.connectedCallback();

    expect((settingsComponent as any)._settings).toEqual(settings);
    expect((settingsComponent as any)._systemPrompt).toBe('Prompt from server');
    expect((settingsComponent as any)._voice).toBe('serena');
    expect((settingsComponent as any)._egressMode).toBe('ALLOW_LIST');
    expect((settingsComponent as any)._networkProfileLoadError).toBe(false);
    expect((settingsComponent as any)._loading).toBe(false);
  });

  it('renders all settings sections and the effective configuration summary', () => {
    const settingsComponent = component();
    (settingsComponent as any)._settings = settings;
    (settingsComponent as any)._appVersion = '0.1.0';
    (settingsComponent as any)._systemPrompt = 'Answer clearly.';

    let rendered = flattenLit(settingsComponent.render());
    expect(rendered).toContain('System Prompt');
    expect(rendered).toContain('Effective LLM configuration');
    expect(rendered).toContain('Vault priority: enabled');
    expect(rendered).toContain('openrouter');

    (settingsComponent as any)._activeTab = 'voice';
    rendered = flattenLit(settingsComponent.render());
    expect(rendered).toContain('Synthesized Voice');
    expect(rendered).toContain('serena');

    (settingsComponent as any)._activeTab = 'network';
    (settingsComponent as any)._egressMode = 'ALLOW_LIST';
    rendered = flattenLit(settingsComponent.render());
    expect(rendered).toContain('Allowed Hosts');
    expect(rendered).toContain('Require Egress Proxy');

    (settingsComponent as any)._activeTab = 'connectors';
    (settingsComponent as any)._githubConnectorEnabled = true;
    (settingsComponent as any)._githubAppConfigured = true;
    (settingsComponent as any)._docsStatus = {
      state: 'INDEXED',
      indexed: true,
      repository: 'rsrini7/AmshaAgent',
      defaultBranch: 'main',
      commitSha: '0fc0ae1a1234',
      documents: [{ path: 'README.md', commitSha: '0fc0ae1a1234', blobSha: 'abc' }],
    };
    rendered = flattenLit(settingsComponent.render());
    expect(rendered).toContain('GitHub Documentation Connector');
    expect(rendered).toContain('rsrini7/AmshaAgent');
    expect(rendered).toContain('INDEXED');

    (settingsComponent as any)._activeTab = 'danger';
    rendered = flattenLit(settingsComponent.render());
    expect(rendered).toContain('Reset & Re-Onboard');
    (settingsComponent as any)._skipOnboarding = true;
    expect(flattenLit((settingsComponent as any)._renderDanger())).toContain('Reset');
  });

  it('saves prompts and voice settings and reports provider errors', async () => {
    const settingsComponent = component();
    const patchPrompt = vi.spyOn(ArcClient.prototype, 'patchPrompt').mockResolvedValue({});
    const patchVoice = vi.spyOn(ArcClient.prototype, 'patchVoice').mockResolvedValue({ voice: 'serena', speechOutputMode: 'TEXT_ONLY' });
    (settingsComponent as any)._systemPrompt = 'Use concise answers.';
    await (settingsComponent as any)._savePrompt();
    expect(patchPrompt).toHaveBeenCalledWith('Use concise answers.');
    expect((settingsComponent as any)._success).toContain('Prompt updated');

    (settingsComponent as any)._voice = 'serena';
    (settingsComponent as any)._speechOutputMode = 'TEXT_ONLY';
    await (settingsComponent as any)._saveVoice();
    expect(patchVoice).toHaveBeenCalledWith('serena', 'TEXT_ONLY');
    expect((settingsComponent as any)._success).toContain('Speech & voice settings updated');

    patchPrompt.mockRejectedValueOnce(new Error('prompt rejected'));
    await (settingsComponent as any)._savePrompt();
    expect((settingsComponent as any)._error).toBe('prompt rejected');
    expect((settingsComponent as any)._loading).toBe(false);
  });

  it('validates and saves network egress, proxy, timeout, and certificate settings', async () => {
    const settingsComponent = component();
    const patch = vi.spyOn(ArcClient.prototype, 'patchConnectionNetworkProfile').mockResolvedValue({ updated: true });
    (settingsComponent as any)._egressMode = 'ALLOW_LIST';
    (settingsComponent as any)._allowedHosts = ' api.example.test, ,docs.example.test ';
    (settingsComponent as any)._requireProxy = true;
    (settingsComponent as any)._proxyUrl = 'https://proxy.example.test';
    (settingsComponent as any)._proxyCredentialVaultRef = 'vault:proxy';
    (settingsComponent as any)._noProxyHosts = 'localhost, 127.0.0.1';
    (settingsComponent as any)._connectTimeoutMs = 5000;
    (settingsComponent as any)._readTimeoutMs = 10000;
    (settingsComponent as any)._tlsTrustMode = 'CUSTOM_CA';
    (settingsComponent as any)._customCaVaultRef = 'vault:ca';

    await (settingsComponent as any)._saveNetworkProfile();

    expect(patch).toHaveBeenCalledWith(-1, expect.objectContaining({
      egressMode: 'ALLOW_LIST',
      allowedHosts: ['api.example.test', 'docs.example.test'],
      requireProxy: true,
      proxyUrl: 'https://proxy.example.test',
      proxyCredentialVaultRef: 'vault:proxy',
      noProxyHosts: ['localhost', '127.0.0.1'],
      connectTimeoutMs: 5000,
      readTimeoutMs: 10000,
      tlsTrustMode: 'CUSTOM_CA',
      customCaVaultRef: 'vault:ca',
    }));
    expect((settingsComponent as any)._success).toContain('Network profile policy updated');
    expect((settingsComponent as any)._isHttpUrl('http://proxy.example.test')).toBe(true);
    expect((settingsComponent as any)._isHttpUrl('file:///etc/passwd')).toBe(false);
    expect((settingsComponent as any)._isHttpUrl('not a url')).toBe(false);

    (settingsComponent as any)._proxyUrl = 'invalid';
    await (settingsComponent as any)._saveNetworkProfile();
    expect((settingsComponent as any)._error).toContain('Proxy URL must be an absolute');
    (settingsComponent as any)._proxyUrl = 'https://proxy.example.test';
    (settingsComponent as any)._connectTimeoutMs = 300001;
    await (settingsComponent as any)._saveNetworkProfile();
    expect((settingsComponent as any)._error).toContain('Connect timeout');
  });

  it('honors deny-all confirmation and protects reset navigation', async () => {
    const settingsComponent = component();
    const confirmStub = vi.fn(() => false);
    vi.stubGlobal('confirm', confirmStub);
    const patch = vi.spyOn(ArcClient.prototype, 'patchConnectionNetworkProfile').mockResolvedValue({ updated: true });
    (settingsComponent as any)._egressMode = 'DENY_ALL';
    await (settingsComponent as any)._saveNetworkProfile();
    expect(patch).not.toHaveBeenCalled();

    const resetOnboarding = vi.spyOn(ArcClient.prototype, 'resetOnboarding').mockResolvedValue({});
    const reload = vi.fn();
    const windowStub = { location: { hash: '', reload } };
    vi.stubGlobal('window', windowStub);
    await (settingsComponent as any)._reset();
    expect(resetOnboarding).not.toHaveBeenCalled();

    confirmStub.mockReturnValue(true);
    await (settingsComponent as any)._reset();
    expect(resetOnboarding).toHaveBeenCalledOnce();
    expect(windowStub.location.hash).toBe('#/onboarding');
    expect(reload).toHaveBeenCalledOnce();
  });
});
