import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ArcShell } from './arc-shell';

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>();

  get length() {
    return this.values.size;
  }

  clear() {
    this.values.clear();
  }

  getItem(key: string) {
    return this.values.get(key) ?? null;
  }

  key(index: number) {
    return Array.from(this.values.keys())[index] ?? null;
  }

  removeItem(key: string) {
    this.values.delete(key);
  }

  setItem(key: string, value: string) {
    this.values.set(key, value);
  }
}

describe('ArcShell workspace authentication lifecycle', () => {
  beforeEach(() => {
    vi.stubGlobal('sessionStorage', new MemoryStorage());
    vi.stubGlobal('localStorage', new MemoryStorage());
    vi.stubGlobal('window', {
      location: { hash: '#/chat' },
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      setTimeout: vi.fn(),
      clearTimeout: vi.fn(),
      dispatchEvent: vi.fn(),
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('replaces a stale stored workspace with an active membership workspace', async () => {
    sessionStorage.setItem('amsha-workspace-id', 'stale-workspace');
    localStorage.setItem('amsha-owner-api-key', 'test-owner-key');
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        memberships: [{ workspaceId: 'active-workspace', status: 'ACTIVE', role: 'OWNER' }],
      }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const shell = new ArcShell();
    await (shell as any)._fetchActiveWorkspace();

    expect(sessionStorage.getItem('amsha-workspace-id')).toBe('active-workspace');
    expect((shell as any)._appContext.workspaceId).toBe('active-workspace');
    expect(fetchMock.mock.calls[0]?.[1]?.headers['X-AMSHA-API-KEY']).toBe('test-owner-key');
  });

  it('prefers an active owner workspace when no workspace is selected', async () => {
    localStorage.setItem('amsha-owner-api-key', 'test-owner-key');
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        memberships: [
          { workspaceId: 'member-workspace', status: 'ACTIVE', role: 'MEMBER' },
          { workspaceId: 'owner-workspace', status: 'ACTIVE', role: 'OWNER' },
        ],
      }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const shell = new ArcShell();
    await (shell as any)._fetchActiveWorkspace();

    expect(sessionStorage.getItem('amsha-workspace-id')).toBe('owner-workspace');
  });

  it('preserves an explicitly selected active member workspace', async () => {
    sessionStorage.setItem('amsha-workspace-id', 'member-workspace');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        memberships: [
          { workspaceId: 'member-workspace', status: 'ACTIVE', role: 'MEMBER' },
          { workspaceId: 'owner-workspace', status: 'ACTIVE', role: 'OWNER' },
        ],
      }),
    }));

    const shell = new ArcShell();
    await (shell as any)._fetchActiveWorkspace();

    expect(sessionStorage.getItem('amsha-workspace-id')).toBe('member-workspace');
  });

  it('clears a stored workspace when the authenticated user has no active membership', async () => {
    sessionStorage.setItem('amsha-workspace-id', 'stale-workspace');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ memberships: [] }) }));

    const shell = new ArcShell();
    await (shell as any)._fetchActiveWorkspace();

    expect(sessionStorage.getItem('amsha-workspace-id')).toBeNull();
    expect((shell as any)._appContext.workspaceId).toBeUndefined();
  });

  it('clears a stale workspace when identity authentication is rejected', async () => {
    sessionStorage.setItem('amsha-workspace-id', 'stale-workspace');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));

    const shell = new ArcShell();
    await (shell as any)._fetchActiveWorkspace();

    expect(sessionStorage.getItem('amsha-workspace-id')).toBeNull();
    expect((shell as any)._appContext.workspaceId).toBeUndefined();
  });
});
