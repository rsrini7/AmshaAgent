import { LitElement, css, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';

export interface SafeMetadata {
  symbol: string;
  station: string;
  label: string;
  auditHash: string;
  subsystem?: string | null;
  safetyInvariant?: string | null;
}

@customElement('arc-node-card')
export class ArcNodeCard extends LitElement {
  @property({ type: String }) label = '';
  @property({ type: String }) station = '';
  @property({ type: String }) status = 'IDLE';
  @property({ type: String }) riskLevel: string | null = null;
  @property({ type: String }) symbol: string | null = null;
  @property({ type: String }) auditHash: string | null = null;
  @property({ type: Boolean }) isInternal = false;
  @property({ type: String }) subsystem: string | null = null;
  @property({ type: String }) safetyInvariant: string | null = null;

  @state() private _xrayOpen = false;
  @state() private _loadingMetadata = false;
  @state() private _metadata: SafeMetadata | null = null;
  @state() private _metadataError = '';

  public async toggleXRay(): Promise<void> {
    this._xrayOpen = !this._xrayOpen;
    if (this._xrayOpen && !this._metadata && this.symbol && !this._loadingMetadata) {
      await this.fetchSymbolication();
    }
  }

  public async fetchSymbolication(): Promise<void> {
    if (!this.symbol) return;
    this._loadingMetadata = true;
    this._metadataError = '';
    try {
      const headers: Record<string, string> = { Accept: 'application/json' };
      const apiKey = typeof localStorage !== 'undefined' ? localStorage.getItem('amsha-owner-api-key')?.trim() : '';
      if (apiKey && apiKey !== 'authenticated') {
        headers['X-AMSHA-API-KEY'] = apiKey;
      }
      const res = await fetch(`/api/v1/cockpit/symbolicate?symbol=${encodeURIComponent(this.symbol)}`, {
        credentials: 'include',
        headers,
      });
      if (res.ok) {
        this._metadata = await res.json();
      } else if (res.status === 404) {
        this._metadataError = 'No verified symbolication available in server allowlist.';
      } else {
        this._metadataError = `Symbolication query failed (HTTP ${res.status}).`;
      }
    } catch {
      this._metadataError = 'Unable to reach symbolication endpoint.';
    } finally {
      this._loadingMetadata = false;
    }
  }

  static styles = css`
    :host {
      display: block;
      font-family: inherit;
    }

    .node-card {
      background: var(--bg-surface, #131722);
      border: 1px solid var(--border, #242938);
      border-radius: 12px;
      padding: 14px;
      display: flex;
      flex-direction: column;
      gap: 10px;
      transition: border-color 0.2s, box-shadow 0.2s;
      position: relative;
    }

    .node-card:hover {
      border-color: color-mix(in srgb, var(--accent, #6c63ff) 40%, transparent);
    }

    .node-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .node-title {
      font-weight: 700;
      font-size: 13px;
      color: var(--text-primary, #ffffff);
    }

    .status-badge {
      font-size: 10px;
      padding: 2px 6px;
      border-radius: 999px;
      text-transform: uppercase;
      font-weight: 700;
      letter-spacing: 0.04em;
    }

    .status-COMPLETED {
      background: color-mix(in srgb, var(--accent-secondary, #00d4aa) 15%, transparent);
      color: var(--accent-secondary, #00d4aa);
    }

    .status-ACTIVE {
      background: color-mix(in srgb, var(--accent, #6c63ff) 20%, transparent);
      color: var(--accent, #6c63ff);
    }

    .status-ERROR, .status-DENIED {
      background: rgba(239, 68, 68, 0.15);
      color: #ef4444;
    }

    .status-HITL_REQUIRED {
      background: rgba(245, 158, 11, 0.2);
      color: #f59e0b;
    }

    .status-LOOP_STUCK {
      background: rgba(239, 68, 68, 0.2);
      color: #ef4444;
    }

    .status-IDLE {
      background: transparent;
      border: 1px solid var(--border, #242938);
      color: var(--text-muted, #8b949e);
    }

    .node-body {
      display: flex;
      flex-direction: column;
      gap: 4px;
      font-size: 11px;
      color: var(--text-muted, #8b949e);
    }

    .risk-indicator {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: 10px;
      color: #f59e0b;
      font-weight: 600;
    }

    .xray-btn {
      align-self: flex-start;
      background: transparent;
      border: 1px solid var(--border, #242938);
      color: var(--text-muted, #8b949e);
      font-size: 10px;
      padding: 2px 8px;
      border-radius: 4px;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 4px;
      margin-top: 4px;
    }

    .xray-btn:hover {
      background: var(--bg-elevated, #1a202c);
      color: var(--text-primary, #ffffff);
    }

    .xray-drawer {
      margin-top: 8px;
      padding: 10px;
      background: color-mix(in srgb, var(--bg-base, #0a0b0f) 85%, transparent);
      border: 1px solid var(--border, #242938);
      border-radius: 8px;
      display: flex;
      flex-direction: column;
      gap: 6px;
      font-size: 11px;
    }

    .xray-row {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }

    .xray-label {
      font-size: 9px;
      text-transform: uppercase;
      color: var(--text-muted, #8b949e);
      letter-spacing: 0.05em;
    }

    .xray-value {
      font-family: monospace;
      color: var(--text-primary, #ffffff);
      word-break: break-all;
    }

    .internal-badge {
      background: rgba(108, 99, 255, 0.2);
      color: var(--accent, #6c63ff);
      font-size: 9px;
      padding: 1px 5px;
      border-radius: 3px;
      width: fit-content;
      margin-bottom: 4px;
      text-transform: uppercase;
      font-weight: 600;
    }

    @media (prefers-reduced-motion: reduce) {
      .node-card, .xray-btn {
        transition: none !important;
      }
    }
  `;

  render() {
    const effectiveSymbol = this._metadata?.symbol || this.symbol || 'unobserved';
    const effectiveLabel = this._metadata?.label || this.label || 'unspecified';
    const effectiveStation = this._metadata?.station || this.station || 'unspecified';
    const effectiveAuditHash = this._metadata?.auditHash || this.auditHash || 'unspecified';
    const effectiveSubsystem = this._metadata ? this._metadata.subsystem : (this.isInternal ? this.subsystem : null);
    const effectiveInvariant = this._metadata ? this._metadata.safetyInvariant : (this.isInternal ? this.safetyInvariant : null);
    const hasOperatorMetadata = Boolean(effectiveSubsystem || effectiveInvariant);

    const isObserved = Boolean(this.symbol) && this.status !== 'IDLE';

    return html`
      <div class="node-card status-${this.status}" role="region" aria-label="Node: ${this.label}">
        <div class="node-header">
          <span class="node-title">${this.label}</span>
          <span class="status-badge status-${this.status}">${this.status}</span>
        </div>

        <div class="node-body">
          ${this.station ? html`<div>Station: <span>${this.station}</span></div>` : nothing}
          ${this.riskLevel ? html`<div class="risk-indicator">⚠ Risk: ${this.riskLevel}</div>` : nothing}
        </div>

        ${isObserved
          ? html`
              <button
                class="xray-btn"
                @click="${this.toggleXRay}"
                aria-expanded="${this._xrayOpen}"
                aria-label="Toggle X-Ray metadata for ${this.label}"
              >
                ${this._xrayOpen ? 'Close X-Ray' : 'Inspect X-Ray'}
              </button>
            `
          : html`<span style="font-size: 10px; color: var(--text-muted, #8b949e); margin-top: 4px;">Unobserved in current run</span>`}

        ${this._xrayOpen && isObserved
          ? html`
              <div class="xray-drawer" role="complementary" aria-label="X-Ray Safe Metadata">
                ${this._loadingMetadata
                  ? html`<div style="font-size: 10px; color: var(--text-muted, #8b949e);">Fetching verified audit proof from server...</div>`
                  : nothing}

                ${this._metadataError
                  ? html`<div style="font-size: 10px; color: #f59e0b;">${this._metadataError}</div>`
                  : nothing}

                ${hasOperatorMetadata
                  ? html`<div class="internal-badge">Operator Gated Metadata</div>`
                  : nothing}

                <div class="xray-row">
                  <span class="xray-label">Label</span>
                  <span class="xray-value xray-label">${effectiveLabel}</span>
                </div>

                <div class="xray-row">
                  <span class="xray-label">Symbol</span>
                  <span class="xray-value xray-symbol">${effectiveSymbol}</span>
                </div>

                <div class="xray-row">
                  <span class="xray-label">Station</span>
                  <span class="xray-value xray-station">${effectiveStation}</span>
                </div>

                <div class="xray-row">
                  <span class="xray-label">Audit Proof (Hash)</span>
                  <span class="xray-value xray-audit-hash">${effectiveAuditHash}</span>
                </div>

                ${effectiveSubsystem
                  ? html`
                      <div class="xray-row">
                        <span class="xray-label">Subsystem</span>
                        <span class="xray-value xray-subsystem">${effectiveSubsystem}</span>
                      </div>
                    `
                  : nothing}

                ${effectiveInvariant
                  ? html`
                      <div class="xray-row">
                        <span class="xray-label">Safety Invariant</span>
                        <span class="xray-value xray-invariant">${effectiveInvariant}</span>
                      </div>
                    `
                  : nothing}
              </div>
            `
          : nothing}
      </div>
    `;
  }
}
