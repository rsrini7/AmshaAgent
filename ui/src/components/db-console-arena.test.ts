import { describe, expect, it } from 'vitest';
import { ArcDbConsole } from './arc-db-console';
import { ArcModelArena } from './arc-model-arena';

describe('ArcDbConsole Component & Fallback Behavior (Issue 13)', () => {
  it('renders initial query interface with quick view templates', () => {
    const consoleComp = new ArcDbConsole();
    expect(consoleComp.sql).toContain('session_history_view');
    expect(consoleComp.limit).toBe(50);

    const rendered = consoleComp.render();
    const str = JSON.stringify(rendered);
    expect(str).toContain('Read-Only Database Console');
    expect(str).toContain('session_catalog');
    expect(str).toContain('session_history');
    expect(str).toContain('recent_traces');
    expect(str).toContain('memory_chunks');
    expect(str).toContain('table_list');
    expect(str).toContain('Run Query');
  });

  it('forwards workspaceId and sessionId in query request body', async () => {
    const consoleComp = new ArcDbConsole();
    (consoleComp as any)._appContext = {
      apiKey: 'test-key',
      sessionId: 'test-session-123',
      workspaceId: 'ws-test-456',
      platformId: 'test-plat',
      sseStatus: 'connected',
    };
    consoleComp.sql = 'SELECT * FROM session_history_view LIMIT 10;';

    const originalFetch = globalThis.fetch;
    let interceptedBody: any = null;
    try {
      globalThis.fetch = async (_url: any, init: any) => {
        interceptedBody = JSON.parse(init.body);
        return {
          ok: true,
          status: 200,
          text: async () => JSON.stringify({ columns: ['id'], rows: [], executionMs: 2, truncated: false }),
        } as any;
      };

      await consoleComp.executeQuery();
      expect(interceptedBody).not.toBeNull();
      expect(interceptedBody.workspaceId).toBe('ws-test-456');
      expect(interceptedBody.sessionId).toBe('test-session-123');
      expect(interceptedBody.sql).toBe('SELECT * FROM session_history_view LIMIT 10;');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it('displays quiet indicator "Operator Surface Unavailable (Internal Role Required)" on 403 forbidden', async () => {
    const consoleComp = new ArcDbConsole();
    consoleComp.sql = 'SELECT * FROM session_history_view;';

    const originalFetch = globalThis.fetch;
    try {
      globalThis.fetch = async (url: any, init: any) => {
        expect(String(url)).toContain('/api/v1/admin/db/query');
        expect(init?.credentials).toBe('include');
        expect(init?.method).toBe('POST');
        return {
          ok: false,
          status: 403,
          json: async () => ({ error: 'Forbidden: internal role required' }),
        } as any;
      };

      await consoleComp.executeQuery();
      const rendered = consoleComp.render();
      const str = JSON.stringify(rendered);

      expect(str).toContain('Operator Surface Unavailable (Internal Role Required)');
      // Never synthesize mock data on 403
      expect(str).not.toContain('table-container');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it('displays "Authentication Expired or Missing API Key (401)" on 401 unauthorized', async () => {
    const consoleComp = new ArcDbConsole();
    consoleComp.sql = 'SELECT * FROM session_history_view;';

    const originalFetch = globalThis.fetch;
    try {
      globalThis.fetch = async (url: any, _init: any) => {
        expect(String(url)).toContain('/api/v1/admin/db/query');
        return {
          ok: false,
          status: 401,
          json: async () => ({ error: 'Unauthorized: missing or invalid credentials' }),
        } as any;
      };

      await consoleComp.executeQuery();
      const rendered = consoleComp.render();
      const str = JSON.stringify(rendered);

      expect(str).toContain('Authentication Expired or Missing API Key (401)');
      expect(str).not.toContain('Operator Surface Unavailable (Internal Role Required)');
      expect(str).not.toContain('table-container');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it('displays error banner and not "Internal Role Required" when 403 is caused by CSRF failure', async () => {
    const consoleComp = new ArcDbConsole();
    consoleComp.sql = 'SELECT * FROM session_history_view;';

    const originalFetch = globalThis.fetch;
    try {
      globalThis.fetch = async (url: any, _init: any) => {
        expect(String(url)).toContain('/api/v1/admin/db/query');
        return {
          ok: false,
          status: 403,
          json: async () => ({ error: 'FORBIDDEN', message: 'CSRF token is missing' }),
        } as any;
      };

      await consoleComp.executeQuery();
      const rendered = consoleComp.render();
      const str = JSON.stringify(rendered);

      expect(str).toContain('CSRF token is missing');
      expect(str).not.toContain('Operator Surface Unavailable (Internal Role Required)');
      expect(str).not.toContain('table-container');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it('reads CSRF token from document cookie and forwards X-CSRF-Token header in ArcClient', async () => {
    const originalDocument = globalThis.document;
    const originalFetch = globalThis.fetch;
    let interceptedHeaders: any = null;

    try {
      (globalThis as any).document = {
        cookie: 'foo=bar; X-CSRF-Token=csrf-secret-12345; other=baz',
      };
      globalThis.fetch = async (_url: any, init: any) => {
        interceptedHeaders = new Headers(init?.headers);
        return {
          ok: true,
          status: 200,
          text: async () => JSON.stringify({ columns: ['ok'], rows: [], executionMs: 1, truncated: false }),
        } as any;
      };

      const { ArcClient } = await import('../api/client');
      const client = new ArcClient('/api/v1');
      await client.queryDb({ sql: 'PRAGMA table_list;' });

      expect(interceptedHeaders).not.toBeNull();
      expect(interceptedHeaders?.get('X-CSRF-Token')).toBe('csrf-secret-12345');
    } finally {
      (globalThis as any).document = originalDocument;
      globalThis.fetch = originalFetch;
    }
  });

  it('renders table columns, rows, and execution metadata on 200 success', async () => {
    const consoleComp = new ArcDbConsole();
    consoleComp.sql = 'PRAGMA table_list;';

    const originalFetch = globalThis.fetch;
    try {
      globalThis.fetch = async () =>
        ({
          ok: true,
          status: 200,
          text: async () =>
            JSON.stringify({
              columns: ['schema', 'name', 'type'],
              rows: [
                ['main', 'session_catalog', 'table'],
                ['main', 'recall_messages', 'table'],
              ],
              executionMs: 4,
              truncated: false,
            }),
        } as any);

      await consoleComp.executeQuery();
      const rendered = consoleComp.render();
      const str = JSON.stringify(rendered);

      expect(str).toContain('session_catalog');
      expect(str).toContain('recall_messages');
      expect((consoleComp as any)._response?.executionMs).toBe(4);
      expect(str).not.toContain('Operator Surface Unavailable');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it('renders error banner when query fails validation or execution', async () => {
    const consoleComp = new ArcDbConsole();
    consoleComp.sql = 'SELECT 1; DROP TABLE dangerous;';

    const originalFetch = globalThis.fetch;
    try {
      globalThis.fetch = async () =>
        ({
          ok: false,
          status: 400,
          json: async () => ({ error: 'Multi-statement queries are forbidden' }),
        } as any);

      await consoleComp.executeQuery();
      const rendered = consoleComp.render();
      const str = JSON.stringify(rendered);

      expect(str).toContain('Multi-statement queries are forbidden');
      expect(str).not.toContain('table-container');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });
});

describe('ArcModelArena Component & Fallback Behavior (Issue 13)', () => {
  it('renders initial model selection chips, prompt, and max tokens', () => {
    const arena = new ArcModelArena();
    expect(arena.selectedModels).toEqual(['omlx', 'generic-openai']);
    expect(arena.maxTokens).toBe(512);

    const rendered = arena.render();
    const str = JSON.stringify(rendered);
    expect(str).toContain('Model Arena');
    expect(str).toContain('omlx');
    expect(str).toContain('generic-openai');
    expect(str).toContain('Run Benchmark');
  });

  it('allows selecting up to 3 models', () => {
    const arena = new ArcModelArena();
    (arena as any)._toggleModel('cerebras');
    expect(arena.selectedModels).toEqual(['omlx', 'generic-openai', 'cerebras']);

    // Attempting to add a 4th model is ignored
    (arena as any)._toggleModel('groq');
    expect(arena.selectedModels).toHaveLength(3);
    expect(arena.selectedModels).not.toContain('groq');
  });

  it('displays quiet indicator "Operator Surface Unavailable (Internal Role Required)" on 403 forbidden', async () => {
    const arena = new ArcModelArena();
    arena.prompt = 'Benchmark prompt';

    const originalFetch = globalThis.fetch;
    try {
      globalThis.fetch = async (url: any, init: any) => {
        expect(String(url)).toContain('/api/v1/admin/arena/compare');
        expect(init?.credentials).toBe('include');
        expect(init?.method).toBe('POST');
        return {
          ok: false,
          status: 403,
          json: async () => ({ error: 'Forbidden: internal role required' }),
        } as any;
      };

      await arena.runCompare();
      const rendered = arena.render();
      const str = JSON.stringify(rendered);

      expect(str).toContain('Operator Surface Unavailable (Internal Role Required)');
      // Never fabricate mock benchmark latencies or model outputs on 403
      expect(str).not.toContain('results-grid');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it('displays "Authentication Expired or Missing API Key (401)" on 401 unauthorized', async () => {
    const arena = new ArcModelArena();
    arena.prompt = 'Benchmark prompt';

    const originalFetch = globalThis.fetch;
    try {
      globalThis.fetch = async (url: any, _init: any) => {
        expect(String(url)).toContain('/api/v1/admin/arena/compare');
        return {
          ok: false,
          status: 401,
          json: async () => ({ error: 'Unauthorized: missing or invalid credentials' }),
        } as any;
      };

      await arena.runCompare();
      const rendered = arena.render();
      const str = JSON.stringify(rendered);

      expect(str).toContain('Authentication Expired or Missing API Key (401)');
      expect(str).not.toContain('Operator Surface Unavailable (Internal Role Required)');
      expect(str).not.toContain('results-grid');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it('renders parallel model cards with telemetry on 200 success', async () => {
    const arena = new ArcModelArena();
    arena.prompt = 'Test comparison';

    const originalFetch = globalThis.fetch;
    try {
      globalThis.fetch = async () =>
        ({
          ok: true,
          status: 200,
          text: async () =>
            JSON.stringify({
              results: [
                {
                  modelSlot: 'omlx',
                  output: 'Local OMLX reasoning response',
                  inputTokens: 12,
                  outputTokens: 25,
                  durationMs: 450,
                  costUsd: 0.0,
                },
                {
                  modelSlot: 'generic-openai',
                  output: 'Generic OpenAI reasoning response',
                  inputTokens: 12,
                  outputTokens: 28,
                  durationMs: 820,
                  costUsd: 0.00015,
                },
              ],
              totalDurationMs: 830,
            }),
        } as any);

      await arena.runCompare();
      const rendered = arena.render();
      const str = JSON.stringify(rendered);

      expect(str).toContain('Local OMLX reasoning response');
      expect(str).toContain('Generic OpenAI reasoning response');
      expect(str).toContain('omlx');
      expect(str).toContain('generic-openai');
      expect((arena as any)._response?.totalDurationMs).toBe(830);
      expect((arena as any)._response?.results[0].durationMs).toBe(450);
      expect(str).not.toContain('Operator Surface Unavailable');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it('renders error banner when credential violation or failure occurs', async () => {
    const arena = new ArcModelArena();
    arena.prompt = 'Check my key sk-1234567890abcdef1234567890abcdef';

    const originalFetch = globalThis.fetch;
    try {
      globalThis.fetch = async () =>
        ({
          ok: false,
          status: 400,
          json: async () => ({ error: 'Submitted prompt contains prohibited credential or secret tokens' }),
        } as any);

      await arena.runCompare();
      const rendered = arena.render();
      const str = JSON.stringify(rendered);

      expect(str).toContain('Submitted prompt contains prohibited credential or secret tokens');
      expect(str).not.toContain('results-grid');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });
});
