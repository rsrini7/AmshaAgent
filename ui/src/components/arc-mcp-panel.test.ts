import { LitElement } from 'lit';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ArcClient } from '../api/client';
import type {
  McpConnection,
  McpDiscoveryPreview,
  McpImportEntry,
  McpImportPreview,
  McpPendingApproval,
} from '../api/client';
import { ArcMcpPanel } from './arc-mcp-panel';

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

const connection: McpConnection = {
  name: 'docs-server',
  endpoint: 'https://mcp.example.test',
  state: 'CONNECTED',
  transport: 'streamable-http',
  enabledToolCount: 1,
  discoveredToolCount: 2,
  revision: 3,
  headers: { Authorization: 'hidden-value' },
  tools: [
    { id: 'tool-1', toolName: 'search_docs', enabled: true, description: 'Search docs', inputSchema: '{}', revision: 3 },
    { id: 'tool-2', toolName: 'read_doc', enabled: false, description: '', inputSchema: '', revision: 3 },
  ],
};

const pending: McpPendingApproval = {
  taskId: 'approval-1',
  operation: 'CONNECT',
  name: 'docs-server',
  endpoint: 'https://mcp.example.test',
  expiresAt: '2026-10-24T13:00:00Z',
};

const discovery: McpDiscoveryPreview = {
  name: 'new-server',
  endpoint: 'https://new.example.test',
  transport: 'streamable-http',
  tools: [{ name: 'lookup', description: 'Look things up', inputSchema: '{}', schemaHash: 'hash' }],
};

function panel(): ArcMcpPanel {
  const component = new ArcMcpPanel();
  (component as any)._appContext = { apiKey: 'test-api-key' };
  return component;
}

function stubWindow(hash = '#/mcp?') {
  const windowStub = {
    location: { hash, href: 'https://amsha.example.test/#/mcp' },
    history: { replaceState: vi.fn() },
  };
  vi.stubGlobal('window', windowStub);
  return windowStub;
}

describe('ArcMcpPanel', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('initializes connections and redeems create, edit, and invalid setup links', async () => {
    vi.spyOn(LitElement.prototype, 'connectedCallback').mockImplementation(() => {});
    const windowStub = stubWindow('#/mcp');
    vi.spyOn(ArcClient.prototype, 'getMcpConnections')
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([connection]);
    vi.spyOn(ArcClient.prototype, 'getMcpApprovals').mockResolvedValue([]);
    const redeem = vi.spyOn(ArcClient.prototype, 'redeemMcpSetup')
      .mockResolvedValueOnce({
        serverName: 'new-server',
        channelType: 'web',
        mode: 'CREATE',
        expiresAt: '2026-10-24T13:00:00Z',
      })
      .mockResolvedValueOnce({
        serverName: 'docs-server',
        channelType: 'web',
        mode: 'EDIT',
        expiresAt: '2026-10-24T13:00:00Z',
      })
      .mockRejectedValueOnce(new Error('expired'));
    const component = panel();

    await (component as any)._init();
    expect((component as any)._connections).toEqual([]);
    expect((component as any)._loading).toBe(false);

    windowStub.location.hash = '#/mcp?token=create-token';
    windowStub.location.href = 'https://amsha.example.test/#/mcp?token=create-token';
    expect(await (component as any)._redeemSetupToken()).toBe(false);
    expect((component as any)._message).toContain('Setup link accepted');

    windowStub.location.hash = '#/mcp?token=edit-token';
    windowStub.location.href = 'https://amsha.example.test/#/mcp?token=edit-token';
    expect(await (component as any)._redeemSetupToken()).toBe(true);
    expect((component as any)._editing).toBe('docs-server');
    expect((component as any)._message).toContain('Secure edit link accepted');

    windowStub.location.hash = '#/mcp?token=expired-token';
    expect(await (component as any)._redeemSetupToken()).toBe(false);
    expect((component as any)._error).toContain('setup link');
    expect(redeem).toHaveBeenCalledTimes(3);
    expect(windowStub.history.replaceState).toHaveBeenCalledTimes(3);
  });

  it('loads and restores matching approval state, then discovers a preview safely', async () => {
    vi.spyOn(ArcClient.prototype, 'getMcpConnections').mockResolvedValue([{
      ...connection,
      state: 'PENDING_APPROVAL',
    }]);
    vi.spyOn(ArcClient.prototype, 'getMcpApprovals').mockResolvedValue([pending]);
    vi.spyOn(ArcClient.prototype, 'discoverMcp').mockResolvedValue(discovery);
    const component = panel();
    await (component as any)._load();
    await (component as any)._restorePendingApproval();

    expect((component as any)._connections[0].state).toBe('PENDING_APPROVAL');
    expect((component as any)._pendingApproval).toBe(pending);

    (component as any)._name = 'new-server';
    (component as any)._url = 'https://new.example.test';
    (component as any)._headers = '{"Authorization":"test"}';
    await (component as any)._discover();

    expect((component as any)._connections.at(-1)).toMatchObject({
      name: 'new-server',
      state: 'PREVIEW',
      discoveredToolCount: 1,
    });
    expect((component as any)._expanded.has('new-server')).toBe(true);
    expect((component as any)._name).toBe('');
    expect((component as any)._message).toBeTruthy();

    (component as any)._name = '';
    await (component as any)._discover();
    expect((component as any)._error).toContain('required');
    (component as any)._name = 'new-server';
    (component as any)._url = 'https://new.example.test';
    (component as any)._headers = '{bad json';
    await (component as any)._discover();
    expect((component as any)._error).toContain('valid JSON');
  });

  it('connects previews, handles approval outcomes, and rejects pending requests', async () => {
    vi.spyOn(ArcClient.prototype, 'connectMcp').mockResolvedValue(connection);
    vi.spyOn(ArcClient.prototype, 'approveMcp')
      .mockResolvedValueOnce({ taskId: pending.taskId, operation: 'CONNECT', result: connection })
      .mockResolvedValueOnce({ taskId: pending.taskId, operation: 'DISCOVER', result: discovery });
    const rejectMcp = vi.spyOn(ArcClient.prototype, 'rejectMcp').mockResolvedValue(undefined);
    const component = panel();
    (component as any)._connections = [{ ...connection, state: 'PREVIEW' }];
    await (component as any)._connectPreview((component as any)._connections[0]);
    expect((component as any)._connections[0].state).toBe('CONNECTED');
    expect((component as any)._message).toContain('Connected docs-server');

    (component as any)._connections = [{ ...connection, state: 'CONNECTED' }];
    await (component as any)._connectPreview((component as any)._connections[0]);
    expect(ArcClient.prototype.connectMcp).toHaveBeenCalledOnce();

    (component as any)._pendingApproval = pending;
    await (component as any)._approvePending();
    expect((component as any)._connections.at(-1).name).toBe('docs-server');
    expect((component as any)._pendingApproval).toBeUndefined();

    (component as any)._pendingApproval = { ...pending, operation: 'DISCOVER', name: 'new-server' };
    (component as any)._headers = '{"Authorization":"test"}';
    await (component as any)._approvePending();
    expect((component as any)._connections.at(-1)).toMatchObject({ state: 'PREVIEW', name: 'new-server' });

    (component as any)._pendingApproval = pending;
    await (component as any)._rejectPending();
    expect(rejectMcp).toHaveBeenCalledWith(pending.taskId);
    expect((component as any)._pendingApproval).toBeUndefined();
  });

  it('applies connection actions, handles revision conflicts, and gates tool changes', async () => {
    const component = panel();
    (component as any)._connections = [connection];
    const approved = { ...connection, revision: 4 };
    const success = await (component as any)._action(async () => approved, 'Updated server.');
    expect(success).toBe(true);
    expect((component as any)._connections[0].revision).toBe(4);
    expect((component as any)._message).toBe('Updated server.');

    const pendingResult = await (component as any)._action(async () => pending);
    expect(pendingResult).toBe(true);
    expect((component as any)._pendingApproval).toBe(pending);

    vi.spyOn(ArcClient.prototype, 'getMcpConnections').mockResolvedValue([approved]);
    const failed = await (component as any)._action(async () => {
      throw new Error('revision conflict');
    });
    expect(failed).toBe(false);
    expect((component as any)._message).toContain('Refreshed current state');

    (component as any)._pendingApproval = undefined;
    vi.spyOn(ArcClient.prototype, 'enableMcpTool').mockResolvedValue(approved);
    await (component as any)._toggleTool(approved, 'read_doc', true);
    expect((component as any)._message).toBe('Enabled read_doc.');

    (component as any)._busy = true;
    const enable = vi.mocked(ArcClient.prototype.enableMcpTool);
    await (component as any)._toggleTool(approved, 'read_doc', true);
    expect(enable).toHaveBeenCalledOnce();
  });

  it('validates edit and import forms and renders server and approval states', async () => {
    const component = panel();
    (component as any)._connections = [connection];
    (component as any)._startEdit(connection);
    expect((component as any)._editing).toBe('docs-server');
    expect(flattenLit((component as any)._renderEditForm())).toContain('Replacement headers JSON');

    (component as any)._editHeaders = '{bad json';
    await (component as any)._saveEdit(connection);
    expect((component as any)._error).toContain('valid JSON');
    (component as any)._editHeaders = '{"Authorization":"replacement"}';
    (component as any)._clearCredentials = true;
    await (component as any)._saveEdit(connection);
    expect((component as any)._error).toContain('not both');

    vi.spyOn(ArcClient.prototype, 'reconfigureMcp').mockResolvedValue({ ...connection, revision: 4 });
    (component as any)._clearCredentials = false;
    (component as any)._editUrl = ' https://updated.example.test ';
    await (component as any)._saveEdit(connection);
    expect((component as any)._editing).toBeUndefined();

    (component as any)._clearAddServerForm();
    expect((component as any)._name).toBe('');
    expect((component as any)._headers).toBe('');

    (component as any)._importPreview = {
      operationId: 'import-1',
      fileName: 'mcp.json',
      expiresAt: '2026-10-24T13:00:00Z',
      entries: [{ name: 'imported', endpoint: 'https://imported.example.test', transport: 'http', supported: true, reason: '' }],
    } satisfies McpImportPreview;
    vi.spyOn(ArcClient.prototype, 'applyMcpImport').mockResolvedValue(pending);
    const entry: McpImportEntry = (component as any)._importPreview.entries[0];
    await (component as any)._applyImport({ ...entry, supported: false });
    expect((component as any)._pendingApproval).toBeUndefined();
    await (component as any)._applyImport(entry);
    expect((component as any)._pendingApproval).toBe(pending);
    expect((component as any)._importPreview).toBeUndefined();

    (component as any)._pendingApproval = pending;
    const rendered = flattenLit(component.render());
    expect(rendered).toContain('Approval required');
    expect(rendered).toContain('docs-server');
    expect(rendered).toContain('Configured MCP servers');
    expect(rendered).not.toContain('hidden-value');
    expect((component as any)._isPending(pending)).toBe(true);
    expect((component as any)._isConnection(connection)).toBe(true);
    expect((component as any)._messageFor('bad response')).toBe('MCP request failed.');
  });
});
