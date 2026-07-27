import { LitElement, css, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { ChannelsConfig } from '../../api/types';
import { formStyles } from '../arc-onboarding-styles';

@customElement('arc-onboarding-step-channels')
export class ArcOnboardingStepChannels extends LitElement {
  static styles = [
    formStyles,
    css`
      :host {
        display: block;
      }
      .channel-card {
        background: var(--bg-elevated);
        border: 1px solid var(--border-card);
        border-radius: var(--radius-md);
        padding: var(--space-4);
        margin-bottom: var(--space-4);
      }
    `
  ];

  @property({ type: Object }) channels: ChannelsConfig = {
    telegramEnabled: false,
    telegramBotTokenVaultRef: '',
    telegramAllowedIds: [],
    webEnabled: true,
    webRequireAuth: true,
    timezone: 'UTC',
    cronStartupMs: 5000,
    phoenixEnabled: false,
    phoenixEndpoint: 'http://localhost:6006'
  };

  private _onChange(key: keyof ChannelsConfig, value: any) {
    const updated = { ...this.channels, [key]: value };
    this.dispatchEvent(
      new CustomEvent('update-channels', {
        detail: { channels: updated },
        bubbles: true,
        composed: true
      })
    );
  }

  private _next() {
    this.dispatchEvent(new CustomEvent('next', { bubbles: true, composed: true }));
  }

  private _back() {
    this.dispatchEvent(new CustomEvent('back', { bubbles: true, composed: true }));
  }

  render() {
    return html`
      <span class="field-label">Channels & Scheduling (Optional)</span>
      <p style="font-size:var(--font-size-xs);color:var(--text-muted);margin-bottom:var(--space-4)">
        Enable Telegram bot chat handlers, web security, and timezone scheduling.
      </p>

      <div style="display:flex;flex-direction:column;gap:var(--space-2);margin-bottom:var(--space-5)">
        <!-- Telegram bot settings -->
        <div class="channel-card">
          <label style="display:flex;align-items:center;gap:var(--space-2);font-size:var(--font-size-sm);color:var(--text-primary);font-weight:var(--font-weight-semi);margin-bottom:var(--space-3)">
            <input type="checkbox" ?checked="${this.channels.telegramEnabled}"
                   @change="${(e: any) => this._onChange('telegramEnabled', e.target.checked)}" />
            Enable Telegram Integration
          </label>
          ${this.channels.telegramEnabled
            ? html`
                <div style="margin-top:var(--space-3)">
                  <span class="field-label">Telegram Bot Token</span>
                  <input class="field-input" type="password" placeholder="Enter Bot token"
                         .value="${this.channels.telegramBotTokenVaultRef || ''}"
                         @input="${(e: any) => this._onChange('telegramBotTokenVaultRef', e.target.value)}" />
                </div>
              `
            : ''}
        </div>

        <!-- Web chat settings -->
        <div class="channel-card">
          <label style="display:flex;align-items:center;gap:var(--space-2);font-size:var(--font-size-sm);color:var(--text-primary);font-weight:var(--font-weight-semi);margin-bottom:var(--space-3)">
            <input type="checkbox" ?checked="${this.channels.webEnabled}"
                   @change="${(e: any) => this._onChange('webEnabled', e.target.checked)}" />
            Enable Web Chat Channel
          </label>
          ${this.channels.webEnabled
            ? html`
                <label style="display:flex;align-items:center;gap:var(--space-2);font-size:12px;color:var(--text-muted);margin-top:var(--space-2)">
                  <input type="checkbox" ?checked="${this.channels.webRequireAuth}"
                         @change="${(e: any) => this._onChange('webRequireAuth', e.target.checked)}" />
                  Enforce Authentication on Web Channel
                </label>
              `
            : ''}
        </div>

        <!-- Scheduling -->
        <div class="channel-card">
          <span class="field-label">Default Timezone</span>
          <input class="field-input" type="text" placeholder="e.g. UTC, Asia/Kolkata"
                 .value="${this.channels.timezone || 'UTC'}"
                 @input="${(e: any) => this._onChange('timezone', e.target.value)}" />
        </div>
      </div>

      <div class="btn-row">
        <button class="btn-ghost" @click="${this._back}">← Back</button>
        <button class="btn-primary" @click="${this._next}">Continue →</button>
      </div>
    `;
  }
}
