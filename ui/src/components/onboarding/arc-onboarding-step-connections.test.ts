import { describe, expect, it } from 'vitest';
import { ArcOnboardingStepConnections } from './arc-onboarding-step-connections';

describe('onboarding connection configuration', () => {
  it('loads provider defaults and toggles capabilities', () => {
    const step = new ArcOnboardingStepConnections() as any;
    step.catalog = [{
      id: 'groq', displayName: 'Groq', requiresApiKey: true,
      modelCatalog: [{ id: 'llama-3.3-70b', label: 'Llama 3.3 70B' }],
    }];
    step.modelDefaults = { groq: { defaultModel: 'llama-3.3-70b' } };

    step.updated(new Map([['catalog', undefined]]));
    expect(step._modelId).toBe('llama-3.3-70b');
    expect(step._discoveredModels).toEqual(step.catalog[0].modelCatalog);
    step._onProviderSelect('groq');
    expect(step._selectedProviderId).toBe('groq');

    step._toggleCapability('vision');
    expect(step._capabilities).toContain('vision');
    step._toggleCapability('vision');
    expect(step._capabilities).not.toContain('vision');
  });

  it('validates and adds connections with API key and generic endpoint rules', () => {
    const step = new ArcOnboardingStepConnections() as any;
    step.catalog = [
      { id: 'groq', displayName: 'Groq', requiresApiKey: true, modelCatalog: [{ id: 'llama-3.3-70b', label: 'Llama' }] },
      { id: 'generic-openai', displayName: 'Generic OpenAI', requiresApiKey: false, modelCatalog: [{ id: 'gpt-4o-mini', label: 'GPT-4o mini' }] },
    ];
    step.modelDefaults = { groq: { defaultModel: 'llama-3.3-70b' }, 'generic-openai': { defaultModel: 'gpt-4o-mini' } };
    const updates: any[][] = [];
    step.addEventListener('update-connections', (event: CustomEvent) => updates.push(event.detail.connections));

    step._onProviderSelect('groq');
    expect(step._connectionValidationMessage()).toContain('API key');
    step._addConnection();
    expect(updates).toHaveLength(0);

    step._apiKey = 'test-provider-key';
    step._addConnection();
    expect(updates).toHaveLength(1);
    expect(updates[0][0]).toMatchObject({
      provider: 'groq', modelId: 'llama-3.3-70b', apiKey: 'test-provider-key',
      profiles: [{ modelId: 'llama-3.3-70b', enabled: true }],
    });
    expect(step._apiKey).toBe('');

    step._onProviderSelect('generic-openai');
    step._targetUrl = 'file:///tmp/model';
    step._addConnection();
    expect(updates).toHaveLength(1);
    step._targetUrl = 'https://llm.example.test/v1';
    step._addConnection();
    expect(updates).toHaveLength(2);
    expect(updates[1][0].targetUrl).toBe('https://llm.example.test/v1');
  });

  it('adds model profiles, enforces the last-profile guard, and removes connections', () => {
    const step = new ArcOnboardingStepConnections() as any;
    const updates: any[][] = [];
    step.addEventListener('update-connections', (event: CustomEvent) => updates.push(event.detail.connections));
    const connection = {
      id: 'conn-1', provider: 'ollama', modelId: 'qwen2.5', structuredOutputMode: 'NATIVE',
      profiles: [],
    };
    step.connections = [connection];

    step._startAddProfile({ ...connection, id: '' });
    expect(step._addingProfileConnId).toMatch(/^conn-/);
    step._newProfileModelId = '   ';
    step._commitAddProfile(connection);
    expect(updates).toHaveLength(0);

    step._newProfileModelId = 'llama3.1';
    step._commitAddProfile(connection);
    expect(updates).toHaveLength(1);
    expect(updates[0][0].profiles.map((profile: any) => profile.modelId)).toEqual(['qwen2.5', 'llama3.1']);
    step._cancelAddProfile();
    expect(step._addingProfileConnId).toBeNull();

    const withProfiles = updates[0][0];
    step._removeProfileFromConnection(withProfiles, withProfiles.profiles[0].id);
    expect(updates[1][0].profiles).toHaveLength(1);
    step._removeProfileFromConnection(updates[1][0], updates[1][0].profiles[0].id);
    expect(updates).toHaveLength(2);

    step.connections = [connection];
    step._removeConnection(0);
    expect(updates[2]).toEqual([]);

    let advanced = false;
    step.addEventListener('next', () => { advanced = true; });
    step._onNext();
    expect(advanced).toBe(true);
  });
});
