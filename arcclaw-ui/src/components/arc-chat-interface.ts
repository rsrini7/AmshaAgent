import { LitElement, css, html } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { WsManager } from '../api/websocket';
import { marked } from 'marked';

interface ChatMessage {
  id: string;
  role: 'user' | 'assistant' | 'system';
  text: string;
  timestamp: number;
  isApprovalPrompt?: boolean;
}

@customElement('arc-chat-interface')
export class ArcChatInterface extends LitElement {
  @property({ type: String }) apiKey = '';
  @property({ type: String }) sessionId = '';

  @state() private _messages: ChatMessage[] = [];
  @state() private _input = '';
  @state() private _wsStatus: 'connecting' | 'open' | 'closed' | 'error' = 'closed';
  @state() private _awaitingApproval = false;
  @state() private _denyReason = '';
  @state() private _showDenyReason = false;

  private _ws: WsManager | null = null;

  static styles = css`
    :host { display: flex; flex-direction: column; height: 100%; box-sizing: border-box; }
    h2 { color: var(--color-text-primary); margin: var(--space-6) var(--space-6) var(--space-2); font-size: var(--font-size-lg); flex-shrink: 0; }
    
    .messages {
      flex: 1; overflow-y: auto; display: flex; flex-direction: column;
      gap: var(--space-4); padding: var(--space-4) var(--space-6);
    }
    
    .msg { 
      max-width: 85%; padding: var(--space-3) var(--space-4); border-radius: var(--radius-lg); 
      font-size: var(--font-size-sm); line-height: 1.5; color: var(--color-text-primary);
      word-break: break-word;
    }
    .msg-user {
      align-self: flex-end; background: var(--color-user-bubble);
      border-bottom-right-radius: var(--radius-sm);
    }
    .msg-assistant {
      align-self: flex-start; background: var(--color-assistant-bubble);
      border: 1px solid var(--color-border); border-bottom-left-radius: var(--radius-sm);
    }
    .msg-system {
      align-self: center; background: transparent; color: var(--color-text-secondary);
      font-style: italic; font-size: 0.8rem; border: none; padding: var(--space-2);
    }
    .msg-approval {
      align-self: stretch; max-width: 100%; background: var(--color-warning-bg);
      border: 1px solid var(--color-warning); border-radius: var(--radius-md);
    }
    
    .input-area {
      padding: var(--space-4) var(--space-6); border-top: 1px solid var(--color-border);
      background: var(--color-bg-base); flex-shrink: 0;
    }
    .input-row { display: flex; gap: var(--space-3); }
    textarea {
      flex: 1; background: var(--color-bg-surface); border: 1px solid var(--color-border);
      border-radius: var(--radius-md); color: var(--color-text-primary);
      padding: var(--space-3); font-size: var(--font-size-sm); resize: none;
      font-family: inherit; min-height: 48px; max-height: 150px; outline: none;
    }
    textarea:focus { border-color: var(--color-accent); }
    button {
      background: var(--color-success); border: none; border-radius: var(--radius-md); 
      color: white; cursor: pointer; padding: 0 var(--space-4); 
      font-size: var(--font-size-sm); font-weight: var(--font-weight-medium);
      transition: background 0.2s; white-space: nowrap;
    }
    button:hover:not(:disabled) { background: #16a34a; }
    button:disabled { background: var(--color-bg-elevated); color: var(--color-text-secondary); cursor: not-allowed; }
    
    .approval-actions { display: flex; gap: var(--space-3); width: 100%; align-items: stretch; }
    .btn-deny { background: transparent; border: 1px solid var(--color-error); color: var(--color-error); }
    .btn-deny:hover:not(:disabled) { background: rgba(239, 68, 68, 0.1); }
    
    /* Markdown Styles */
    .msg p { margin: 0 0 0.5em 0; }
    .msg p:last-child { margin: 0; }
    .msg pre { background: var(--color-bg-base); padding: var(--space-3); border-radius: var(--radius-sm); overflow-x: auto; }
    .msg code { font-family: 'Fira Code', monospace; font-size: 0.85em; background: rgba(0,0,0,0.3); padding: 2px 4px; border-radius: 4px; }
    .msg pre code { background: transparent; padding: 0; }
    
    .status-text { font-size: 0.8rem; color: var(--color-text-secondary); margin-bottom: var(--space-2); }
  `;

  connectedCallback() {
    super.connectedCallback();
    if (this.apiKey) {
      this._connect();
    }
  }
  
  disconnectedCallback() {
    super.disconnectedCallback();
    this._ws?.disconnect();
  }

  private _connect() {
    const wsUrl = `ws://localhost:8080/ws/chat?key=${encodeURIComponent(this.apiKey)}`;
    this._ws = new WsManager(
      wsUrl,
      (frame) => this._handleFrame(frame),
      (status) => this._wsStatus = status
    );
    this._ws.connect();
  }

  private _handleFrame(frame: any) {
    if (frame.type === 'connected') {
      this.dispatchEvent(new CustomEvent('session-ready', {
        detail: { sessionId: frame.sessionId, platformId: frame.platformId },
        bubbles: true, composed: true
      }));
      this._messages = [...this._messages, {
        id: crypto.randomUUID(), role: 'system', text: 'Connected to ArcClaw Agent Cluster', timestamp: Date.now()
      }];
    } 
    else if (frame.type === 'message' && frame.role === 'assistant') {
      const isApproval = frame.text.includes('⏸ Approval needed');
      
      this._messages = [...this._messages, {
        id: crypto.randomUUID(),
        role: 'assistant',
        text: frame.text,
        timestamp: Date.now(),
        isApprovalPrompt: isApproval
      }];
      
      if (isApproval) {
        this._awaitingApproval = true;
      }
      
      this._scrollToBottom();
    }
  }

  private _send() {
    const text = this._input.trim();
    if (!text || this._wsStatus !== 'open') return;
    
    this._messages = [...this._messages, {
      id: crypto.randomUUID(), role: 'user', text, timestamp: Date.now()
    }];
    
    this._ws?.send({ type: 'message', text });
    this._input = '';
    this._scrollToBottom();
  }
  
  private _sendApprove() {
    this._ws?.send({ type: 'message', text: '/approve' });
    this._awaitingApproval = false;
    this._showDenyReason = false;
    this._messages = [...this._messages, { id: crypto.randomUUID(), role: 'user', text: '/approve', timestamp: Date.now() }];
    this._scrollToBottom();
  }
  
  private _sendDeny() {
    if (!this._showDenyReason) {
      this._showDenyReason = true;
      return;
    }
    const reasonText = this._denyReason.trim() ? `/deny ${this._denyReason.trim()}` : '/deny';
    this._ws?.send({ type: 'message', text: reasonText });
    this._awaitingApproval = false;
    this._showDenyReason = false;
    this._denyReason = '';
    this._messages = [...this._messages, { id: crypto.randomUUID(), role: 'user', text: reasonText, timestamp: Date.now() }];
    this._scrollToBottom();
  }

  private _scrollToBottom() {
    // Wait for render cycle to complete
    setTimeout(() => {
      const el = this.shadowRoot?.querySelector('.messages');
      if (el) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
    }, 50);
  }
  
  private _renderMarkdown(text: string) {
    try {
      // Create DOM element from parsed markdown to safely inject HTML
      const template = document.createElement('div');
      template.innerHTML = marked.parse(text, { async: false }) as string;
      return template;
    } catch {
      return text;
    }
  }

  render() {
    return html`
      <h2>Chat Session</h2>
      <div class="messages">
        ${this._messages.map(m => html`
          <div class="msg ${m.role === 'user' ? 'msg-user' : m.role === 'system' ? 'msg-system' : m.isApprovalPrompt ? 'msg-approval' : 'msg-assistant'}">
            ${m.role === 'user' || m.role === 'system' ? m.text : this._renderMarkdown(m.text)}
          </div>
        `)}
      </div>
      
      <div class="input-area">
        ${this._wsStatus !== 'open' 
          ? html`<div class="status-text">WebSocket: ${this._wsStatus}...</div>` 
          : ''}
          
        ${this._awaitingApproval 
          ? html`
            <div class="approval-actions">
              ${this._showDenyReason
                ? html`
                  <textarea 
                    placeholder="Reason for denial (optional)..."
                    .value="${this._denyReason}"
                    @input="${(e: Event) => this._denyReason = (e.target as HTMLTextAreaElement).value}"
                    @keydown="${(e: KeyboardEvent) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); this._sendDeny(); } }}"
                  ></textarea>
                  <button class="btn-deny" @click="${this._sendDeny}">Confirm Deny</button>
                  <button style="background: transparent; border: none; color: var(--color-text-secondary);" @click="${() => this._showDenyReason = false}">Cancel</button>
                `
                : html`
                  <button @click="${this._sendApprove}">✓ Approve Task</button>
                  <button class="btn-deny" @click="${this._sendDeny}">✗ Deny Task</button>
                `
              }
            </div>
          `
          : html`
            <div class="input-row">
              <textarea
                placeholder="Ask ArcClaw anything…"
                .value="${this._input}"
                ?disabled="${this._wsStatus !== 'open'}"
                @input="${(e: Event) => this._input = (e.target as HTMLTextAreaElement).value}"
                @keydown="${(e: KeyboardEvent) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); this._send(); } }}"
              ></textarea>
              <button ?disabled="${this._wsStatus !== 'open' || !this._input.trim()}" @click="${this._send}">Send</button>
            </div>
          `
        }
      </div>
    `;
  }
}
