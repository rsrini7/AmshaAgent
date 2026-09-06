import { LitElement, css, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { consume } from '@lit/context';
import { arcContext, ArcAppContext } from '../context/arc-context';
import { ArcClient, ArcApiError, DbQueryResponse } from '../api/client';

@customElement('arc-db-console')
export class ArcDbConsole extends LitElement {
  @consume({ context: arcContext, subscribe: true })
  @state()
  private _appContext!: ArcAppContext;

  @property({ type: String }) sql = 'SELECT * FROM session_history_view LIMIT 20;';
  @property({ type: Number }) limit = 50;

  @state() private _loading = false;
  @state() private _error: string | null = null;
  @state() private _isUnavailable = false;
  @state() private _authStatus: 401 | 403 | null = null;
  @state() private _response: DbQueryResponse | null = null;

  static styles = css`
    :host {
      display: flex;
      flex-direction: column;
      height: 100%;
      padding: var(--space-6, 24px);
      box-sizing: border-box;
      gap: var(--space-4, 16px);
      background: var(--bg-base, #0a0b0f);
      color: var(--text-primary, #ffffff);
      overflow-y: auto;
    }

    .header {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      flex-wrap: wrap;
      gap: 12px;
    }

    .title-group h2 {
      margin: 0 0 4px 0;
      font-size: 1.25rem;
      font-weight: 700;
      letter-spacing: -0.02em;
    }

    .subtitle {
      margin: 0;
      font-size: 12px;
      color: var(--text-muted, #8b949e);
    }

    .query-box {
      display: flex;
      flex-direction: column;
      gap: 8px;
      background: var(--bg-surface, #131722);
      border: 1px solid var(--border, #242938);
      border-radius: 8px;
      padding: 16px;
    }

    .quick-queries {
      display: flex;
      align-items: center;
      gap: 6px;
      flex-wrap: wrap;
      font-size: 11px;
    }

    .quick-queries span {
      color: var(--text-muted, #8b949e);
      font-weight: 600;
    }

    .quick-btn {
      background: color-mix(in srgb, var(--accent, #6c63ff) 12%, transparent);
      border: 1px solid color-mix(in srgb, var(--accent, #6c63ff) 30%, transparent);
      color: var(--text-primary, #ffffff);
      padding: 3px 8px;
      font-size: 11px;
      border-radius: 4px;
      cursor: pointer;
      font-family: monospace;
    }

    .quick-btn:hover {
      background: color-mix(in srgb, var(--accent, #6c63ff) 25%, transparent);
    }

    textarea {
      width: 100%;
      box-sizing: border-box;
      background: var(--bg-elevated, #1a202c);
      border: 1px solid var(--border, #242938);
      color: var(--text-primary, #ffffff);
      font-family: monospace;
      font-size: 13px;
      padding: 10px;
      border-radius: 6px;
      resize: vertical;
      min-height: 70px;
    }

    textarea:focus {
      outline: none;
      border-color: var(--accent, #6c63ff);
    }

    .query-actions {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
    }

    .query-actions-right {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    select, button {
      padding: 6px 12px;
      border-radius: 6px;
      border: 1px solid var(--border, #242938);
      background: var(--bg-elevated, #1a202c);
      color: var(--text-primary, #ffffff);
      font-size: 12px;
      cursor: pointer;
      font-weight: 600;
    }

    .execute-btn {
      background: var(--accent, #6c63ff);
      border-color: var(--accent, #6c63ff);
      color: #ffffff;
    }

    .execute-btn:hover:not(:disabled) {
      background: color-mix(in srgb, var(--accent, #6c63ff) 80%, #ffffff);
    }

    .execute-btn:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }

    .char-counter {
      font-size: 11px;
      color: var(--text-muted, #8b949e);
    }

    .unavailable-notice {
      padding: 16px 20px;
      border-radius: 8px;
      background: rgba(245, 158, 11, 0.1);
      border: 1px solid rgba(245, 158, 11, 0.3);
      color: #f59e0b;
      font-size: 13px;
      font-weight: 600;
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .error-banner {
      padding: 12px 16px;
      border-radius: 6px;
      background: rgba(239, 68, 68, 0.15);
      border: 1px solid rgba(239, 68, 68, 0.35);
      color: #ef4444;
      font-size: 12px;
      font-family: monospace;
    }

    .results-section {
      display: flex;
      flex-direction: column;
      gap: 10px;
      flex: 1;
      min-height: 0;
    }

    .meta-bar {
      display: flex;
      align-items: center;
      gap: 16px;
      font-size: 12px;
      color: var(--text-muted, #8b949e);
    }

    .meta-value {
      color: var(--text-primary, #ffffff);
      font-weight: 600;
      font-family: monospace;
    }

    .truncated-badge {
      background: rgba(245, 158, 11, 0.2);
      color: #f59e0b;
      padding: 2px 6px;
      border-radius: 4px;
      font-size: 10px;
      font-weight: 700;
      text-transform: uppercase;
    }

    .table-container {
      flex: 1;
      min-height: 0;
      overflow: auto;
      background: var(--bg-surface, #131722);
      border: 1px solid var(--border, #242938);
      border-radius: 8px;
    }

    table {
      width: 100%;
      border-collapse: collapse;
      font-size: 12px;
      font-family: monospace;
    }

    th {
      position: sticky;
      top: 0;
      background: var(--bg-elevated, #1a202c);
      padding: 8px 12px;
      text-align: left;
      border-bottom: 1px solid var(--border, #242938);
      color: var(--text-muted, #8b949e);
      font-weight: 600;
      white-space: nowrap;
    }

    td {
      padding: 6px 12px;
      border-bottom: 1px solid color-mix(in srgb, var(--border, #242938) 50%, transparent);
      white-space: nowrap;
      max-width: 300px;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    tr:hover td {
      background: color-mix(in srgb, var(--accent, #6c63ff) 8%, transparent);
    }
  `;

  async executeQuery() {
    if (!this.sql.trim() || this._loading) return;
    this._loading = true;
    this._error = null;
    this._response = null;
    this._authStatus = null;
    this._isUnavailable = false;

    try {
      const trimmedSql = this.sql.trim();
      const lowerSql = trimmedSql.toLowerCase();
      const needsSession = lowerSql.includes('session_history_view') || lowerSql.includes('recent_traces_view');
      const client = new ArcClient('/api/v1', this._appContext?.apiKey);
      const res = await client.queryDb({
        sql: trimmedSql,
        limit: this.limit,
        workspaceId:
          this._appContext?.workspaceId ||
          (typeof sessionStorage !== 'undefined'
            ? sessionStorage.getItem('amsha-workspace-id') || undefined
            : undefined),
        sessionId: needsSession ? (this._appContext?.sessionId || undefined) : undefined,
      });
      this._response = res;
      this._isUnavailable = false;
      this._authStatus = null;
    } catch (err: any) {
      if (err instanceof ArcApiError && err.status === 401) {
        this._authStatus = 401;
        this._isUnavailable = true;
      } else if (err instanceof ArcApiError && err.status === 403) {
        const msg = (err.message || '').toLowerCase();
        if (msg.includes('csrf')) {
          this._authStatus = null;
          this._isUnavailable = false;
          this._error = err.message || 'CSRF validation failed. Please refresh or re-authenticate.';
        } else {
          this._authStatus = 403;
          this._isUnavailable = true;
        }
      } else {
        this._authStatus = null;
        this._error = err.message || 'Database query execution failed.';
      }
    } finally {
      this._loading = false;
    }
  }

  private _onSqlChange(e: Event) {
    const target = e.target as HTMLTextAreaElement;
    this.sql = target.value;
  }

  private _setQuery(query: string) {
    this.sql = query;
  }

  render() {
    return html`
      <div class="header">
        <div class="title-group">
          <h2>Read-Only Database Console</h2>
          <p class="subtitle">
            In-process SQLite/PostgreSQL introspection. Single-statement SELECT and PRAGMA with tenant isolation.
            ${this._appContext?.workspaceId
              ? html`<span style="margin-left: 0.5rem; padding: 2px 6px; background: var(--bg-surface, #21262d); border: 1px solid var(--border-color, #30363d); border-radius: 4px; font-size: 11px;">Workspace: <code>${this._appContext.workspaceId}</code></span>`
              : nothing}
          </p>
        </div>
      </div>

      ${this._authStatus === 401
        ? html`
            <div class="unavailable-notice" role="alert">
              <span>⚠</span>
              <span>Authentication Expired or Missing API Key (401)</span>
            </div>
          `
        : this._authStatus === 403 || this._isUnavailable
        ? html`
            <div class="unavailable-notice" role="alert">
              <span>⚠</span>
              <span>Operator Surface Unavailable (Internal Role Required)</span>
            </div>
          `
        : nothing}

      <div class="query-box">
        <div class="quick-queries">
          <span>Views:</span>
          <button class="quick-btn" @click="${() => this._setQuery('SELECT * FROM session_catalog_view LIMIT 20;')}">
            session_catalog
          </button>
          <button class="quick-btn" @click="${() => this._setQuery('SELECT * FROM session_history_view LIMIT 20;')}">
            session_history
          </button>
          <button class="quick-btn" @click="${() => this._setQuery('SELECT * FROM recent_traces_view LIMIT 20;')}">
            recent_traces
          </button>
          <button class="quick-btn" @click="${() => this._setQuery('SELECT * FROM memory_chunks_view LIMIT 20;')}">
            memory_chunks
          </button>
          <span>PRAGMA:</span>
          <button class="quick-btn" @click="${() => this._setQuery('PRAGMA table_list;')}">
            table_list
          </button>
          <button class="quick-btn" @click="${() => this._setQuery('PRAGMA table_info(session_catalog);')}">
            table_info
          </button>
        </div>

        <textarea
          .value="${this.sql}"
          @input="${this._onSqlChange}"
          placeholder="Enter read-only SQL query (e.g. SELECT * FROM session_history_view LIMIT 10)..."
          maxlength="1000"
          aria-label="SQL query input"
        ></textarea>

        <div class="query-actions">
          <span class="char-counter">${this.sql.length} / 1000 characters</span>
          <div class="query-actions-right">
            <label style="font-size: 11px; color: var(--text-muted, #8b949e);">
              Limit:
              <select
                .value="${String(this.limit)}"
                @change="${(e: Event) =>
                  (this.limit = Number.parseInt((e.target as HTMLSelectElement).value, 10))}"
              >
                <option value="10">10</option>
                <option value="25">25</option>
                <option value="50">50</option>
                <option value="100">100</option>
              </select>
            </label>
            <button
              class="run-btn"
              @click="${this.executeQuery}"
              ?disabled="${this._loading || !this.sql.trim()}"
            >
              ${this._loading ? 'Executing...' : 'Run Query'}
            </button>
          </div>
        </div>
      </div>

      ${this._error ? html`<div class="error-banner" role="alert">${this._error}</div>` : nothing}

      ${this._response
        ? html`
            <div class="results-section">
              <div class="meta-bar">
                <span>Execution: <span class="meta-value">${this._response.executionMs}ms</span></span>
                <span>Rows: <span class="meta-value">${(this._response.rows || []).length}</span></span>
                ${this._response.truncated
                  ? html`<span class="truncated-badge">Truncated</span>`
                  : nothing}
              </div>
              <div class="table-container">
                <table>
                  <thead>
                    <tr>
                      ${(this._response.columns || []).map((col) => html`<th>${col}</th>`)}
                    </tr>
                  </thead>
                  <tbody>
                    ${(this._response.rows || []).map(
                      (row) => html`
                        <tr>
                          ${(row || []).map((val) => html`<td>${val === null ? 'NULL' : String(val)}</td>`)}
                        </tr>
                      `
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          `
        : nothing}
    `;
  }
}
