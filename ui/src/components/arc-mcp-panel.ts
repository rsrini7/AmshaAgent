import { LitElement, css, html } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { consume } from '@lit/context';
import { arcContext, ArcAppContext } from '../context/arc-context';
import { ArcClient, McpApprovalExecution, McpConnection, McpDiscoveryPreview, McpImportPreview, McpPendingApproval } from '../api/client';

@customElement('arc-mcp-panel')
export class ArcMcpPanel extends LitElement {
  @consume({ context: arcContext, subscribe: true })
  @state()
  private _appContext!: ArcAppContext;

  @state() private _connections: McpConnection[] = [];
  @state() private _name = '';
  @state() private _url = '';
  @state() private _headers = '';
  @state() private _busy = false;
  @state() private _message = '';
  @state() private _error = '';
  @state() private _pendingApproval?: McpPendingApproval;
  @state() private _editing?: string;
 @state() private _editUrl = '';
 @state() private _editHeaders = '';
 @state() private _importPreview?: McpImportPreview;
 @state() private _expanded = new Set<string>();

  static styles = css`
    :host { display: block; padding: 2rem; overflow-y: auto; }
    .header { display:flex; justify-content:space-between; align-items:flex-start; gap:1rem; margin-bottom:1.5rem; }
    h1 { margin:0; font-size:1.5rem; } p { color:var(--text-muted); margin:.4rem 0 0; }
 .card { background:var(--bg-surface); border:1px solid var(--border); border-radius:12px; padding:1.25rem; margin-bottom:1rem; }
 .server-card details { width:100%; }
 .server-card summary { list-style:none; cursor:pointer; }
 .server-card summary::-webkit-details-marker { display:none; }
 .server-summary { padding:.1rem; }
 .server-head { display:flex; justify-content:space-between; gap:1rem; align-items:center; }
 .server-title { display:flex; align-items:center; gap:.65rem; }
 .server-title h2 { margin:0; }
 .server-endpoint { font-family:monospace; overflow-wrap:anywhere; }
 .server-meta { font-size:.8rem; }
 .summary-chevron { color:var(--accent-secondary); font-size:1.25rem; transition:transform .15s ease; }
 .server-card details[open] .summary-chevron { transform:rotate(180deg); }
 .server-content { border-top:1px solid var(--border); margin-top:1rem; padding-top:.25rem; }
 .connections-toolbar { display:flex; justify-content:space-between; align-items:end; gap:1rem; margin:1.5rem 0 .75rem; }
 .connections-toolbar h2 { margin:0; font-size:1.1rem; }
 .empty-state { text-align:center; }
    .form { display:grid; grid-template-columns:1fr 2fr; gap:.75rem; align-items:center; }
    label { color:var(--text-muted); font-size:.85rem; } input, textarea { width:100%; box-sizing:border-box; padding:.65rem; background:var(--bg-base); border:1px solid var(--border); border-radius:7px; color:var(--text-primary); }
    textarea { min-height:4rem; font-family:monospace; } button { border:1px solid var(--border); background:var(--bg-elevated); color:var(--text-primary); border-radius:7px; padding:.55rem .8rem; cursor:pointer; } button.primary { background:var(--accent); color:white; border-color:var(--accent); } button.danger { color:#f87171; } button:disabled { opacity:.5; cursor:wait; }
    .actions { display:flex; gap:.5rem; flex-wrap:wrap; margin-top:1rem; } .server-head { display:flex; justify-content:space-between; gap:1rem; align-items:center; } .state { font-size:.75rem; color:var(--accent-secondary); } .tools { margin-top:1rem; border-top:1px solid var(--border); padding-top:.75rem; } .tool { display:flex; justify-content:space-between; gap:1rem; padding:.55rem 0; border-bottom:1px solid color-mix(in srgb, var(--border) 50%, transparent); } .tool-name { font-family:monospace; } .tool-desc { color:var(--text-muted); font-size:.8rem; margin-top:.2rem; } .notice { color:var(--accent-secondary); margin:.75rem 0; } .error { color:#f87171; margin:.75rem 0; }
    @media(max-width:700px) { :host { padding:1rem; } .form { grid-template-columns:1fr; gap:.35rem; } }
  `;

  connectedCallback() {
    super.connectedCallback();
    void this._load();
    void this._redeemSetupToken();
  }

  private get _client() { return new ArcClient('/api/v1', this._appContext?.apiKey || ''); }

  private async _redeemSetupToken() {
    const query = window.location.hash.split('?')[1];
    const token = query ? new URLSearchParams(query).get('token') : null;
    if (!token) return;
    try {
      const grant = await this._client.redeemMcpSetup(token);
      this._name = grant.serverName;
      this._message = 'Setup link accepted. Enter the endpoint and credentials, then test and discover.';
      window.history.replaceState(null, '', `${window.location.href.split('#')[0]}#/mcp`);
    } catch (error) {
      this._error = this._messageFor(error);
      window.history.replaceState(null, '', `${window.location.href.split('#')[0]}#/mcp`);
    }
  }

  private async _load() {
    try { this._connections = await this._client.getMcpConnections(); this._error = ''; }
    catch (error) { this._error = this._messageFor(error); }
  }

  private async _discover() {
    if (!this._name.trim() || !this._url.trim()) { this._error = 'Name and endpoint are required.'; return; }
    let headers: Record<string, string> = {};
    if (this._headers.trim()) {
      try { headers = JSON.parse(this._headers); }
      catch (_) { this._error = 'Headers must be valid JSON, for example {"authorization":"Bearer …"}.'; return; }
    }
    this._busy = true; this._error = ''; this._message = '';
    try {
      const preview = await this._client.discoverMcp(this._name.trim(), this._url.trim(), headers);
      if (this._isPending(preview)) {
        this._pendingApproval = preview;
        this._message = `Approval required before contacting ${preview.name}.`;
        return;
      }
      const connection: McpConnection = {
        name: preview.name,
        endpoint: preview.endpoint,
        state: 'PREVIEW',
        transport: preview.transport,
        enabledToolCount: 0,
        discoveredToolCount: preview.tools.length,
        revision: 0,
        headers,
        tools: preview.tools.map((tool, index) => ({
          id: `preview-${index}`,
          toolName: tool.name,
          enabled: false,
          description: tool.description,
          inputSchema: tool.inputSchema,
          revision: 0,
        })),
      };
 this._connections = [...this._connections.filter(c => c.name !== connection.name), connection];
 this._expanded = new Set([...this._expanded, connection.name]);
      this._message = `Discovered ${connection.discoveredToolCount} tools. Nothing is enabled yet. Click Connect to persist it.`;
    } catch (error) { this._error = this._messageFor(error); }
    finally { this._busy = false; }
  }

  private async _connectPreview(connection: McpConnection) {
    if (connection.state !== 'PREVIEW') return;
    this._busy = true;
    this._error = '';
    this._message = '';
    try {
      const result = await this._client.connectMcp(
        connection.name,
        connection.endpoint,
        connection.headers || {},
      );
      if (this._isPending(result)) {
        this._pendingApproval = result;
        this._message = `Approval required before connecting ${result.name}.`;
        return;
      }
      this._connections = this._connections.map(candidate =>
        candidate.name === result.name ? result : candidate,
      );
      this._message = `Connected ${result.name}. Select tools explicitly to activate them.`;
    } catch (error) {
      this._error = this._messageFor(error);
    } finally {
      this._busy = false;
    }
  }

  private async _approvePending() {
    if (!this._pendingApproval) return;
    this._busy = true;
    this._error = '';
    try {
      const approval: McpApprovalExecution = await this._client.approveMcp(this._pendingApproval.taskId);
      if (this._isConnection(approval.result)) {
        this._connections = [...this._connections.filter(c => c.name !== approval.result.name), approval.result];
      } else {
        const preview = approval.result;
        const headers = this._headers.trim() ? JSON.parse(this._headers) : {};
        const connection: McpConnection = {
          name: preview.name,
          endpoint: preview.endpoint,
          state: 'PREVIEW',
          transport: preview.transport,
          enabledToolCount: 0,
          discoveredToolCount: preview.tools.length,
          revision: 0,
          headers,
          tools: preview.tools.map((tool, index) => ({ id: `preview-${index}`, toolName: tool.name, enabled: false, description: tool.description, inputSchema: tool.inputSchema, revision: 0 })),
        };
        this._connections = [...this._connections.filter(c => c.name !== connection.name), connection];
      }
      this._pendingApproval = undefined;
      this._message = 'MCP approval completed.';
    } catch (error) { this._error = this._messageFor(error); }
    finally { this._busy = false; }
  }

  private async _rejectPending() {
    if (!this._pendingApproval) return;
    try {
      await this._client.rejectMcp(this._pendingApproval.taskId);
      this._pendingApproval = undefined;
      this._message = 'MCP approval rejected.';
    } catch (error) { this._error = this._messageFor(error); }
  }

  private async _action(action: () => Promise<McpConnection | McpPendingApproval>, message?: string) {
    this._busy = true; this._error = ''; this._message = '';
    try {
      const updated = await action();
      if (this._isPending(updated)) {
        this._pendingApproval = updated;
        this._message = `Approval required for ${updated.operation.toLowerCase()}.`;
      } else {
        this._connections = this._connections.map(c => c.name === updated.name ? updated : c);
        this._message = message || 'MCP connection updated.';
      }
    } catch (error) {
      this._error = this._messageFor(error);
      if (this._error.toLowerCase().includes('revision')) {
        await this._load();
        this._message = 'The connection changed elsewhere. Refreshed current state.';
      }
    }
    finally { this._busy = false; }
  }

  private async _toggleTool(connection: McpConnection, toolName: string, enabled: boolean) {
        if (this._busy || connection.state !== 'CONNECTED' || this._pendingApproval?.name === connection.name) {
            return;
        }
        await this._action(
      () => enabled ? this._client.enableMcpTool(connection.name, toolName, connection.revision) : this._client.disableMcpTool(connection.name, toolName, connection.revision),
      enabled ? `Enabled ${toolName}.` : `Disabled ${toolName}.`);
  }

  private _startEdit(connection: McpConnection) {
    this._editing = connection.name;
    this._editUrl = connection.endpoint;
    this._editHeaders = '';
    this._error = '';
    this._message = 'Stored credentials remain unchanged unless you enter replacement headers.';
  }

  private async _saveEdit(connection: McpConnection) {
    let headers: Record<string, string> = {};
    if (this._editHeaders.trim()) {
      try {
        headers = JSON.parse(this._editHeaders);
      } catch (_) {
        this._error = 'Replacement headers must be valid JSON.';
        return;
      }
    }
    await this._action(
      () => this._client.reconfigureMcp(connection.name, this._editUrl.trim(), headers, connection.revision),
      'Reconfiguration requested.'
    );
    this._editing = undefined;
  }

  private async _previewImport(event: Event) {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) return;
    this._busy = true;
    this._error = '';
    try {
      this._importPreview = await this._client.previewMcpConfig(file);
      this._message = 'Preview parsed locally. Apply a supported entry to request normal MCP approval.';
    } catch (error) {
      this._error = this._messageFor(error);
    } finally {
      this._busy = false;
    }
  }

  private async _applyImport(entry: McpImportPreview['entries'][number]) {
    if (!this._importPreview || !entry.supported) return;
    this._busy = true;
    this._error = '';
    try {
      const result = await this._client.applyMcpImport(this._importPreview.operationId, entry.name);
      this._pendingApproval = result;
      this._importPreview = undefined;
      this._message = `Approval required for imported MCP server ${entry.name}.`;
    } catch (error) {
      this._error = this._messageFor(error);
    } finally {
      this._busy = false;
    }
  }

  private _messageFor(error: unknown) { return error instanceof Error ? error.message : 'MCP request failed.'; }

  private _isPending(value: McpConnection | McpPendingApproval | McpDiscoveryPreview): value is McpPendingApproval {
    return 'taskId' in value;
  }

 private _isConnection(value: McpConnection | McpDiscoveryPreview): value is McpConnection {
  return 'state' in value;
 }

 private _toggleExpanded(name: string, expanded: boolean) {
  const next = new Set(this._expanded);
  if (expanded) next.add(name);
  else next.delete(name);
  this._expanded = next;
 }

 private _setAllExpanded(expanded: boolean) {
  this._expanded = expanded ? new Set(this._connections.map(connection => connection.name)) : new Set();
 }

 private _renderEditForm() {
  if (this._editing) {
   const connection = this._connections.find(item => item.name === this._editing);
   if (!connection) return '';
   return html`<section class="card"><h2>Edit ${connection.name}</h2><div class="form">
    <label for="mcp-edit-url">MCP endpoint</label>
    <input id="mcp-edit-url" .value=${this._editUrl} @input=${(event: InputEvent) => this._editUrl = (event.target as HTMLInputElement).value} />
    <label for="mcp-edit-headers">Replacement headers JSON</label>
    <textarea id="mcp-edit-headers" .value=${this._editHeaders} @input=${(event: InputEvent) => this._editHeaders = (event.target as HTMLTextAreaElement).value} placeholder="Leave blank to keep stored credentials"></textarea>
   </div><div class="actions">
    <button class="primary" @click=${() => this._saveEdit(connection)} ?disabled=${this._busy}>Save and request approval</button>
    <button @click=${() => this._editing = undefined}>Cancel</button>
   </div></section>`;
  }
  return html`<section class="card"><h2>Import MCP config</h2><p>Choose a local .mcp.json file. Preview is bounded and does not contact a server.</p>
   <input type="file" accept=".json,application/json" @change=${this._previewImport} ?disabled=${this._busy} />
   ${this._importPreview ? html`<div class="tools"><p>${this._importPreview.fileName} - expires ${this._importPreview.expiresAt}</p>
    ${this._importPreview.entries.map(entry => html`<div class="tool">
     <span class="tool-name">${entry.name} - ${entry.endpoint || entry.reason}</span>
     <button @click=${() => this._applyImport(entry)} ?disabled=${!entry.supported || this._busy}>${entry.supported ? 'Apply' : 'Unsupported'}</button>
    </div>`)}
   </div>` : ''}
  </section>`;
 }

 private _renderConnectionCard(connection: McpConnection) {
  const isPreview = connection.state === 'PREVIEW';
  const expanded = this._expanded.has(connection.name);
  return html`<section class="card server-card">
   <details .open=${expanded} @toggle=${(event: Event) => this._toggleExpanded(connection.name, (event.target as HTMLDetailsElement).open)}>
    <summary class="server-summary">
     <div class="server-head">
      <div>
       <div class="server-title"><h2>${connection.name}</h2><span class="state">${connection.state}</span></div>
       <p class="server-endpoint">${connection.endpoint}</p>
       <p class="server-meta">${connection.transport} - ${connection.enabledToolCount}/${connection.discoveredToolCount} tools enabled</p>
      </div>
      <span class="summary-chevron" aria-hidden="true">v</span>
     </div>
    </summary>
    <div class="server-content">
     <div class="actions">
      ${isPreview ? html`<button class="primary" @click=${() => this._connectPreview(connection)} ?disabled=${this._busy}>Connect</button>` : html`
       <button @click=${() => this._startEdit(connection)}>Edit</button>
       <button @click=${() => this._action(() => this._client.activateMcp(connection.name, connection.revision), 'Activated enabled tools.')}>Activate</button>
       <button @click=${() => this._action(() => this._client.reconnectMcp(connection.name, connection.revision), 'Reconnected.')}>Reconnect</button>
       <button @click=${() => this._action(() => connection.state === 'PAUSED'
        ? this._client.resumeMcp(connection.name, connection.revision)
        : this._client.pauseMcp(connection.name, connection.revision))}>
        ${connection.state === 'PAUSED' ? 'Resume' : 'Pause'}
       </button>
       <button class="danger" @click=${() => this._action(async () => {
        await this._client.disconnectMcp(connection.name, connection.revision);
        return { ...connection, state: 'DISCONNECTED' };
       }, 'Disconnected.')}>Disconnect</button>
      `}
     </div>
     <div class="tools"><strong>Discovered tools</strong>
      ${connection.tools.length ? connection.tools.map(tool => html`<div class="tool">
       <div><div class="tool-name">${tool.toolName}</div><div class="tool-desc">${tool.description || 'No description provided.'}</div></div>
       ${isPreview ? html`<span class="state">Preview only</span>` : html`<button @click=${() => this._toggleTool(connection, tool.toolName, !tool.enabled)}>${tool.enabled ? 'Disable' : 'Enable'}</button>`}
      </div>`) : html`<p>No tools discovered.</p>`}
     </div>
    </div>
   </details>
  </section>`;
 }

 render() {
  return html`
   ${this._renderEditForm()}
   <div class="header"><div><h1>MCP connections</h1><p>Connect remote MCP servers, review discovered tools, and activate only what you need.</p></div><button @click=${this._load} ?disabled=${this._busy}>Refresh</button></div>
   <section class="card"><h2>Add server</h2><p>Credentials are sent over the authenticated API and stored server-side in the vault; they are never included in chat or Telegram commands.</p>
    <div class="form">
     <label for="mcp-name">Name</label><input id="mcp-name" .value=${this._name} @input=${(event: InputEvent) => this._name = (event.target as HTMLInputElement).value} placeholder="tapetide" />
     <label for="mcp-url">MCP endpoint</label><input id="mcp-url" .value=${this._url} @input=${(event: InputEvent) => this._url = (event.target as HTMLInputElement).value} placeholder="https://example.com/mcp" />
     <label for="mcp-headers">Headers JSON (optional)</label><textarea id="mcp-headers" .value=${this._headers} @input=${(event: InputEvent) => this._headers = (event.target as HTMLTextAreaElement).value} placeholder='{"authorization":"Bearer ..."}'></textarea>
    </div>
    <div class="actions"><button class="primary" @click=${this._discover} ?disabled=${this._busy}>Test and discover</button></div>
   </section>
   ${this._pendingApproval ? html`<div class="notice">Approval required: ${this._pendingApproval.operation} ${this._pendingApproval.name}
    <button class="primary" @click=${this._approvePending} ?disabled=${this._busy}>Approve</button><button @click=${this._rejectPending} ?disabled=${this._busy}>Reject</button>
   </div>` : ''}
   ${this._message ? html`<div class="notice">${this._message}</div>` : ''}
   ${this._error ? html`<div class="error">${this._error}</div>` : ''}
   ${this._connections.length ? html`
    <div class="connections-toolbar"><div><h2>Configured MCP servers</h2><p>${this._connections.length} connection${this._connections.length === 1 ? '' : 's'}</p></div>
     ${this._connections.length > 1 ? html`<div class="actions"><button @click=${() => this._setAllExpanded(true)}>Expand all</button><button @click=${() => this._setAllExpanded(false)}>Collapse all</button></div>` : ''}
    </div>
    ${this._connections.map(connection => this._renderConnectionCard(connection))}
   ` : html`<section class="card empty-state"><h2>No configured MCP servers</h2><p>Use Add server above or import a compatible .mcp.json file.</p></section>`}
  `;
 }
}
