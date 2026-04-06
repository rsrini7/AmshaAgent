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
      height: 100vh; background: var(--bg-base);
    }
    .card {
      background: var(--bg-surface); border: 1px solid var(--border); 
      border-radius: 24px; padding: 48px; 
      width: 420px; display: flex; flex-direction: column; gap: 24px;
      box-shadow: 0 20px 50px rgba(0,0,0,0.5);
      position: relative; overflow: hidden;
    }
    .card::before {
      content: '⬡';
      position: absolute; top: -30px; right: -30px;
      font-size: 15rem; color: var(--accent); opacity: 0.03;
      pointer-events: none;
    }
    .logo-section { display: flex; align-items: center; gap: 12px; }
    .logo-icon { font-size: 28px; color: var(--accent); }
    h1 { 
      font-size: 24px; font-weight: 700; margin: 0;
      background: linear-gradient(135deg, var(--accent) 0%, var(--accent-secondary) 100%);
      -webkit-background-clip: text; -webkit-text-fill-color: transparent;
    }
    p { color: var(--text-muted); font-size: 14px; line-height: 1.6; margin: 0; }
    
    .input-grp { display: flex; flex-direction: column; gap: 8px; }
    label { font-size: 11px; font-weight: 700; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.1em; }
    input {
      background: var(--bg-elevated); border: 1px solid var(--border); 
      border-radius: 12px; color: var(--text-primary); 
      padding: 14px 16px; font-size: 15px; width: 100%;
      font-family: inherit; outline: none; transition: var(--transition);
    }
    input:focus { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-glow); }
    
    button {
      background: linear-gradient(135deg, var(--accent) 0%, var(--accent-secondary) 100%);
      border: none; border-radius: 12px; 
      color: white; cursor: pointer; padding: 14px; 
      font-size: 15px; font-weight: 600;
      transition: transform 0.2s, brightness 0.2s;
    }
    button:hover:not(:disabled) { brightness: 1.1; transform: translateY(-1px); }
    button:disabled { opacity: 0.6; cursor: not-allowed; }
    .error { 
      color: #ef4444; font-size: 12px; background: rgba(239, 68, 68, 0.1); 
      padding: 10px; border-radius: 8px; border: 1px solid rgba(239, 68, 68, 0.2);
    }
  `;

  private async _testConnection() {
    if (!this._inputKey.trim()) return;
    this._testing = true;
    this._error = '';
    
    try {
      const client = new ArcClient('/api/v1', this._inputKey.trim());
      await client.getAgents();
      this._success = true;
      setTimeout(() => {
        this.dispatchEvent(new CustomEvent('api-key-set', { 
           detail: { apiKey: this._inputKey.trim() }, 
           bubbles: true, 
           composed: true 
        }));
      }, 500);
    } catch (err: unknown) {
      if (err instanceof Error && err.message.includes('401')) {
        this._error = 'Invalid API key. Please check your credentials.';
      } else {
        this._error = 'Cannot reach ArcClaw on port 8080. Ensure the backend is running.';
      }
    } finally {
      this._testing = false;
    }
  }

  render() {
    return html`
      <div class="card">
        <div class="logo-section">
           <span class="logo-icon">⬡</span>
           <h1>ArcClaw</h1>
        </div>
        <p>Enter your API key to access the agent cluster. Security is our priority; keys are held in memory only.</p>
        
        <div class="input-grp">
          <label>API Key</label>
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
          ${this._testing ? 'Syncing...' : this._success ? 'Access Granted ✓' : 'Initialize Connection'}
        </button>
      </div>
    `;
  }
}
