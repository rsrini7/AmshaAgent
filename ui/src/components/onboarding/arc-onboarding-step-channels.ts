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
      .setup-card {
        background: var(--bg-elevated);
        border: 1px solid var(--border-card);
        border-radius: var(--radius-md);
        padding: var(--space-4);
        margin-bottom: var(--space-4);
      }
      .setup-card h3 {
        margin: 0 0 var(--space-2);
        color: var(--text-primary);
        font-size: var(--font-size-sm);
      }
      .setup-card p {
        color: var(--text-muted);
        font-size: var(--font-size-xs);
        line-height: 1.6;
      }
      pre {
        white-space: pre-wrap;
        overflow-wrap: anywhere;
        background: var(--bg-surface);
        border: 1px solid var(--border);
        border-radius: var(--radius-sm);
        padding: var(--space-3);
        font-size: var(--font-size-xs);
      }
    `
  ];

  // Retain the persisted shape for existing drafts; channel settings are deployment-level.
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

  private _next() {
    this.dispatchEvent(new CustomEvent('next', { bubbles: true, composed: true }));
  }

  private _back() {
    this.dispatchEvent(new CustomEvent('back', { bubbles: true, composed: true }));
  }

  render() {
    return html`
      <span class="field-label">Channels (Optional)</span>
      <p class="status-msg">
        Channel credentials and access rules belong to the Amsha deployment. This web wizard cannot write them to
        <code>.env</code> or apply them to the running process. Use the startup CLI or deployment configuration, then
        restart Amsha. Do not paste bot tokens here.
      </p>

      <section class="setup-card">
        <h3>Telegram</h3>
        <p>
          The CLI can guide Telegram setup. Restrict access with both allowed-user settings before enabling the bot.
        </p>
        <pre><code>AMSHA_TELEGRAM_ENABLED=true
TELEGRAM_BOT_TOKEN=&lt;token-from-BotFather&gt;
AMSHA_TELEGRAM_ALLOWED_USER_IDS=&lt;numeric-user-ids&gt;
AMSHA_TELEGRAM_ALLOWED_USERS=&lt;usernames-without-at&gt;</code></pre>
      </section>

      <section class="setup-card">
        <h3>Web chat and schedules</h3>
        <p>
          Web access control and channel lifecycle are also deployment settings. Keep web authentication enabled for
          shared or remotely reachable deployments. Schedule time zones can be selected when creating a schedule.
        </p>
        <pre><code>AMSHA_WEB_REQUIRE_AUTH=true</code></pre>
      </section>

      <div class="btn-row">
        <button class="btn-ghost" @click="${this._back}">← Back</button>
        <button class="btn-primary" @click="${this._next}">Continue →</button>
      </div>
    `;
  }
}
