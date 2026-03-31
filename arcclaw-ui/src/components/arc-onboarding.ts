import { LitElement, css, html } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { ArcClient } from '../api/client';

@customElement('arc-onboarding')
export class ArcOnboarding extends LitElement {
  @state() private _inputKey = '';
  @state() private _testing = false;
  @state() private _error = '';
  @state() private _success = false;

  static styles = css`
    :host {
      display: flex; align-items: center; justify-content: center;
      height: 100vh; background: var(--color-bg-base);
    }
    .card {
      background: var(--color-bg-surface); border: 1px solid var(--color-border); 
      border-radius: var(--radius-lg); padding: var(--space-8); 
      width: 420px; display: flex; flex-direction: column; gap: var(--space-4);
      box-shadow: var(--shadow-md);
    }
    h1 { color: var(--color-info); font-size: var(--font-size-lg); margin: 0; }
    p { color: var(--color-text-secondary); font-size: var(--font-size-sm); line-height: 1.5; margin: 0; }
    .input-grp { display: flex; flex-direction: column; gap: var(--space-2); }
    input {
      background: var(--color-bg-base); border: 1px solid var(--color-border); 
      border-radius: var(--radius-md); color: var(--color-text-primary); 
      padding: var(--space-2) var(--space-3); font-size: var(--font-size-base); width: 100%;
      font-family: inherit; outline: none;
    }
    input:focus { border-color: var(--color-accent); }
    button {
      background: var(--color-success); border: none; border-radius: var(--radius-md); 
      color: white; cursor: pointer; padding: var(--space-2) var(--space-4); 
      font-size: var(--font-size-base); font-weight: var(--font-weight-medium);
      transition: background 0.2s;
    }
    button:hover:not(:disabled) { background: #16a34a; }
    button:disabled { opacity: 0.6; cursor: not-allowed; }
    .error { color: var(--color-error); font-size: var(--font-size-sm); }
  `;

  private async _testConnection() {
    if (!this._inputKey.trim()) return;
    this._testing = true;
    this._error = '';
    
    try {
      const client = new ArcClient('/api/v1', this._inputKey.trim());
      await client.getAgents();
      this._success = true;
      this.dispatchEvent(new CustomEvent('api-key-set', { 
        detail: { apiKey: this._inputKey.trim() }, 
        bubbles: true, 
        composed: true 
      }));
    } catch (err: unknown) {
      if (err instanceof Error && err.message.includes('401')) {
        this._error = 'Invalid API key';
      } else {
        this._error = 'Cannot reach ArcClaw on port 8080';
      }
    } finally {
      this._testing = false;
    }
  }

  render() {
    return html`
      <div class="card">
        <h1>🦅 ArcClaw Setup</h1>
        <p>Enter your ArcClaw API key to continue. The key is stored in memory only and cleared when you close the browser tab.</p>
        
        <div class="input-grp">
          <input
            type="password"
            placeholder="arcclaw-key-..."
            .value="${this._inputKey}"
            @input="${(e: InputEvent) => this._inputKey = (e.target as HTMLInputElement).value}"
            @keydown="${(e: KeyboardEvent) => e.key === 'Enter' && this._testConnection()}"
            ?disabled="${this._testing || this._success}"
          />
          ${this._error ? html`<div class="error">${this._error}</div>` : ''}
        </div>
        
        <button 
          @click="${this._testConnection}" 
          ?disabled="${this._testing || !this._inputKey.trim() || this._success}">
          ${this._testing ? 'Connecting...' : this._success ? 'Connected ✓' : 'Connect'}
        </button>
      </div>
    `;
  }
}
