import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  ArcApiError,
  ArcClient,
  clearPersistedOwnerApiKey,
  persistOwnerApiKey,
  readPersistedOwnerApiKey,
} from './client';

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

describe('owner API key persistence', () => {
  beforeEach(() => {
    vi.stubGlobal('localStorage', new MemoryStorage());
    vi.stubGlobal('sessionStorage', new MemoryStorage());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('persists a normalized owner key', () => {
    persistOwnerApiKey('  sk-owner  ');

    expect(readPersistedOwnerApiKey()).toBe('sk-owner');
  });

  it('does not clear a key issued after a failed request started', () => {
    const attemptedKey = readPersistedOwnerApiKey();
    persistOwnerApiKey('sk-new-owner');

    clearPersistedOwnerApiKey(attemptedKey);

    expect(readPersistedOwnerApiKey()).toBe('sk-new-owner');
  });

  it('clears the exact stale key used by a failed request', () => {
    persistOwnerApiKey('sk-stale-owner');

    clearPersistedOwnerApiKey('sk-stale-owner');

    expect(readPersistedOwnerApiKey()).toBe('');
  });

  it('can retry onboarding status with cookie authentication only', async () => {
    persistOwnerApiKey('sk-stale-owner');
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ credentialsOk: false }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await new ArcClient('/api/v1', undefined, false).onboardingStatus();

    const request = fetchMock.mock.calls[0][1] as RequestInit;
    expect(new Headers(request.headers).has('X-AMSHA-API-KEY')).toBe(false);
    expect(request.cache).toBe('no-store');
  });
});

describe('bounded HITL requests', () => {
  beforeEach(() => {
    vi.stubGlobal('localStorage', new MemoryStorage());
    vi.stubGlobal('sessionStorage', new MemoryStorage());
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('sends a stable idempotency key and a bounded approval signal', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ status: 'approved', taskId: 'task-1' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await new ArcClient('/api/v1').approveHitl('task-1', 'approve', {
      idempotencyKey: 'hitl-approve-task-1',
    });

    const request = fetchMock.mock.calls[0][1] as RequestInit;
    expect(new Headers(request.headers).get('X-Idempotency-Key')).toBe('hitl-approve-task-1');
    expect(request.signal).toBeInstanceOf(AbortSignal);
  });

  it('sends toolName in approval request body when specified', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ status: 'approved', taskId: 'task-1' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await new ArcClient('/api/v1').approveHitl('task-1', 'approve', {
      toolName: 'browser_navigate',
    });

    const request = fetchMock.mock.calls[0][1] as RequestInit;
    expect(JSON.parse(request.body as string)).toEqual({
      reason: 'approve',
      toolName: 'browser_navigate',
    });
  });

  it('aborts a stalled approval request instead of waiting indefinitely', async () => {
    vi.useFakeTimers();
    let requestSignal: AbortSignal | null | undefined;
    const fetchMock = vi.fn().mockImplementation((_url: string, init: RequestInit) => {
      requestSignal = init.signal;
      return new Promise((_resolve, reject) => {
        init.signal?.addEventListener('abort', () => reject(init.signal?.reason));
      });
    });
    vi.stubGlobal('fetch', fetchMock);

    const request = new ArcClient('/api/v1').approveHitl('task-timeout');
    void request.catch(() => undefined);
    await vi.advanceTimersByTimeAsync(10_001);

    expect(requestSignal?.aborted).toBe(true);
  });
});

describe('classifier runtime status client', () => {
  beforeEach(() => {
    vi.stubGlobal('localStorage', new MemoryStorage());
    vi.stubGlobal('sessionStorage', new MemoryStorage());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('reads the read-only classifier endpoint without caching', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          classifiers: [
            {
              classifier: 'intent',
              startupEnabled: false,
              requestedEnabled: false,
              implementationAvailable: false,
              effectiveEnabled: false,
              acceptingRequests: false,
              draining: false,
              inFlight: 0,
              revision: 1,
            },
          ],
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    );
    vi.stubGlobal('fetch', fetchMock);

    const result = await new ArcClient('/api/v1', 'real-owner-key').getClassifierRuntimeStatus();

    const [url, request] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/v1/admin/local-ml/onnx/classifiers');
    expect(request.method ?? 'GET').toBe('GET');
    expect(request.cache).toBe('no-store');
    expect(request.credentials).toBe('include');
    expect(result.classifiers).toHaveLength(1);
    expect(result.classifiers[0].implementationAvailable).toBe(false);
  });

  it('propagates the internal-role 403 so the panel can name the cause', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ error: 'Only internal API keys can control ONNX classifiers' }), {
        status: 403,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const failure = await new ArcClient('/api/v1')
      .getClassifierRuntimeStatus()
      .catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ArcApiError);
    expect((failure as ArcApiError).status).toBe(403);
    expect((failure as ArcApiError).message).toContain('internal');
  });
});

describe('cockpit trace client', () => {
  beforeEach(() => {
    vi.stubGlobal('localStorage', new MemoryStorage());
    vi.stubGlobal('sessionStorage', new MemoryStorage());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('filters dummy authenticated key and passes credentials: include', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify([{ runId: 'run-1', sessionId: 'sess-1' }]), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const client = new ArcClient('/api/v1', 'authenticated');
    const runs = await client.getSessionRuns('sess-1', 50, 0);

    expect(runs).toHaveLength(1);
    const [url, request] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/v1/cockpit/sessions/sess-1/runs?limit=50&offset=0');
    expect(request.credentials).toBe('include');
    expect(new Headers(request.headers).has('X-AMSHA-API-KEY')).toBe(false);
  });

  it('fetches run traces with bounded parameters', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify([{ eventId: 'ev-1', runId: 'run-1' }]), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const client = new ArcClient('/api/v1', 'real-owner-key');
    const traces = await client.getRunTrace('run-1', 500, 10);

    expect(traces).toHaveLength(1);
    const [url, request] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/v1/cockpit/runs/run-1/trace?limit=500&offset=10');
    expect(request.credentials).toBe('include');
    expect(new Headers(request.headers).get('X-AMSHA-API-KEY')).toBe('real-owner-key');
  });
});

