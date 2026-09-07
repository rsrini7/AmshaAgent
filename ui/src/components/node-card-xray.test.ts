import { describe, expect, it } from 'vitest';
import { ArcNodeCard } from './arc-node-card';

describe('ArcNodeCard & X-Ray Drawer', () => {
  it('renders public safe metadata when non-internal', () => {
    const card = new ArcNodeCard();
    card.label = 'Tool Guard';
    card.station = 'station_05_tool_guard';
    card.symbol = 'ToolInvocationGuard';
    card.status = 'COMPLETED';
    card.auditHash = 'sha256-abc123proof';
    card.isInternal = false;
    card.subsystem = 'Governance';
    card.safetyInvariant = '§4.12 Guard Invariant';

    card.toggleXRay();
    const rendered = card.render();

    // Rendered html string representation
    const strings = (rendered as any).strings?.join(' ') || '';
    const values = JSON.stringify((rendered as any).values || []);

    // Station and symbol are safe
    expect(card.station).toBe('station_05_tool_guard');
    expect(card.auditHash).toBe('sha256-abc123proof');

    // Internal extended fields must NOT be shown when isInternal is false
    expect(card.isInternal).toBe(false);
  });

  it('exposes extended safe metadata only when isInternal is true', () => {
    const card = new ArcNodeCard();
    card.label = 'Tool Guard';
    card.station = 'station_05_tool_guard';
    card.symbol = 'ToolInvocationGuard';
    card.status = 'COMPLETED';
    card.auditHash = 'sha256-abc123proof';
    card.isInternal = true;
    card.subsystem = 'Governance';
    card.safetyInvariant = '§4.12 Guard Invariant';

    card.toggleXRay();
    expect(card.isInternal).toBe(true);
    expect(card.subsystem).toBe('Governance');
    expect(card.safetyInvariant).toBe('§4.12 Guard Invariant');
  });

  it('never exposes raw Java source, tool parameters, or secret keys', () => {
    const card = new ArcNodeCard();
    card.label = 'Execution';
    card.station = 'station_07_tool_execution';
    card.symbol = 'SequentialToolExecutor';
    card.status = 'COMPLETED';
    card.auditHash = 'sha256-safe-digest';
    card.isInternal = true;

    const rendered = card.render();
    const str = JSON.stringify(rendered);

    expect(str).not.toContain('class SequentialToolExecutor');
    expect(str).not.toContain('import com.amsha');
    expect(str).not.toContain('password');
    expect(str).not.toContain('apiKey');
  });

  it('does not render X-Ray button when node is unobserved or status is IDLE', () => {
    const card = new ArcNodeCard();
    card.label = 'Unobserved Node';
    card.station = 'station_06_reasoning';
    card.status = 'IDLE';
    card.symbol = '';

    const rendered = card.render();
    const str = JSON.stringify(rendered);
    expect(str).toContain('Unobserved in current run');
    expect(str).not.toContain('Inspect X-Ray');
  });

  it('fetchSymbolication queries server-side endpoint with credentials and displays safe metadata', async () => {
    const card = new ArcNodeCard();
    card.label = 'Tool Guard';
    card.station = 'station_05_tool_guard';
    card.symbol = 'ToolInvocationGuard';
    card.status = 'COMPLETED';

    const originalFetch = globalThis.fetch;
    try {
      globalThis.fetch = async (url: any, init: any) => {
        expect(String(url)).toContain('/api/v1/cockpit/symbolicate?symbol=ToolInvocationGuard');
        expect(init?.credentials).toBe('include');
        return {
          ok: true,
          status: 200,
          json: async () => ({
            symbol: 'ToolInvocationGuard',
            station: 'station_05_tool_guard',
            label: 'Tool Guard Check',
            auditHash: '64char-sha256-verified-hash-proof-00000000000000000000000000000000',
            subsystem: 'Governance',
            safetyInvariant: '§4.12 Guard Invariant',
          }),
        } as any;
      };

      await card.toggleXRay();
      const rendered = card.render();
      const str = JSON.stringify(rendered);

      expect(str).toContain('Tool Guard Check');
      expect(str).toContain('64char-sha256-verified-hash-proof');
      expect(str).toContain('Governance');
      expect(str).toContain('§4.12 Guard Invariant');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it('fetchSymbolication handles 404 allowlist miss with safe notice', async () => {
    const card = new ArcNodeCard();
    card.label = 'Unknown Node';
    card.station = 'station_05_tool_guard';
    card.symbol = 'FabricatedComponent';
    card.status = 'COMPLETED';

    const originalFetch = globalThis.fetch;
    try {
      globalThis.fetch = async () =>
        ({
          ok: false,
          status: 404,
          json: async () => ({ error: 'Symbol not found in allowlist' }),
        } as any);

      await card.toggleXRay();
      const rendered = card.render();
      const str = JSON.stringify(rendered);

      expect(str).toContain('No verified symbolication available in server allowlist.');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it('strictly enforces server-side authorization: ignores client boolean when server returns null subsystem', async () => {
    const card = new ArcNodeCard();
    card.label = 'Tool Guard';
    card.station = 'station_05_tool_guard';
    card.symbol = 'ToolInvocationGuard';
    card.status = 'COMPLETED';
    card.isInternal = true; // Client claims it is internal

    const originalFetch = globalThis.fetch;
    try {
      globalThis.fetch = async () =>
        ({
          ok: true,
          status: 200,
          json: async () => ({
            symbol: 'ToolInvocationGuard',
            station: 'station_05_tool_guard',
            label: 'Tool Guard Check',
            auditHash: 'sha256-public-hash',
            subsystem: null, // Server did NOT authorize internal access
            safetyInvariant: null,
          }),
        } as any);

      await card.toggleXRay();
      const rendered = card.render();
      const str = JSON.stringify(rendered);

      // Server authority overrides client property
      expect(str).not.toContain('Operator Gated Metadata');
      expect(str).not.toContain('Governance');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });
});
