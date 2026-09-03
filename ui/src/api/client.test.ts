import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
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
