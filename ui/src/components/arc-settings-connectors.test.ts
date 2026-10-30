import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ArcSettings } from './arc-settings';

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>();

  get length(): number {
    return this.values.size;
  }

  clear(): void {
    this.values.clear();
  }

  getItem(key: string): string | null {
    return this.values.get(key) ?? null;
  }

  key(index: number): string | null {
    return [...this.values.keys()][index] ?? null;
  }

  removeItem(key: string): void {
    this.values.delete(key);
  }

  setItem(key: string, value: string): void {
    this.values.set(key, value);
  }
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('GitHub documentation connector workspace scope', () => {
  beforeEach(() => {
    vi.stubGlobal('localStorage', new MemoryStorage());
    vi.stubGlobal('sessionStorage', new MemoryStorage());
    sessionStorage.setItem('amsha-workspace-id', 'member-workspace');
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('uses an active owner workspace without changing the selected workspace', async () => {
    const status = {
      state: 'INDEXED',
      indexed: true,
      repository: 'rsrini7/AmshaAgent',
      documents: [],
    };
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const workspaceId = new Headers(init?.headers).get('X-Amsha-Workspace-Id');
      if (url.endsWith('/connectors/github-readme/workspaces')) {
        return jsonResponse([
          { workspaceId: 'member-workspace', status: 'ACTIVE', role: 'MEMBER' },
          { workspaceId: 'owner-workspace', status: 'ACTIVE', role: 'OWNER' },
        ]);
      }
      if (url.endsWith('/admin/connectors/github-readme/app-credential')) {
        return jsonResponse({ enabled: true, configured: true });
      }
      if (url.endsWith('/connectors/github-readme/status') || url.endsWith('/connectors/github-readme/connect')) {
        return workspaceId === 'owner-workspace'
          ? jsonResponse(status)
          : jsonResponse({ error: 'forbidden' }, 403);
      }
      throw new Error(`Unexpected request: ${url}`);
    });
    vi.stubGlobal('fetch', fetchMock);

    const settings = new ArcSettings();
    (settings as any)._appContext = {
      apiKey: 'owner-api-key',
      workspaceId: 'member-workspace',
    };

    await (settings as any)._loadConnectorStatus();
    expect((settings as any)._docsStatus).toEqual(status);

    await (settings as any)._connectDocs();
    expect((settings as any)._docsSuccess).toContain('Successfully connected and indexed');

    const connectorRequests = fetchMock.mock.calls.filter(([url]) => {
      const path = String(url);
      return (
        path.endsWith('/connectors/github-readme/status') ||
        path.endsWith('/connectors/github-readme/connect')
      );
    });
    expect(connectorRequests).toHaveLength(2);
    for (const [, init] of connectorRequests) {
      expect(new Headers(init?.headers).get('X-Amsha-Workspace-Id')).toBe('owner-workspace');
    }
    const workspaceRequest = fetchMock.mock.calls.find(([url]) =>
      String(url).endsWith('/connectors/github-readme/workspaces'),
    );
    expect(workspaceRequest).toBeDefined();
    expect(new Headers(workspaceRequest?.[1]?.headers).has('X-Amsha-Workspace-Id')).toBe(false);
    expect(sessionStorage.getItem('amsha-workspace-id')).toBe('member-workspace');
  });

  it('explains owner access is required when the user has no active owner workspace', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith('/connectors/github-readme/workspaces')) {
        return jsonResponse([{ workspaceId: 'member-workspace', status: 'ACTIVE', role: 'MEMBER' }]);
      }
      throw new Error(`Connector request should not be made: ${url}`);
    });
    vi.stubGlobal('fetch', fetchMock);

    const settings = new ArcSettings();
    (settings as any)._appContext = {
      apiKey: 'member-api-key',
      workspaceId: 'member-workspace',
    };

    await (settings as any)._connectDocs();

    expect((settings as any)._docsError).toContain('active owner workspace');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
