import { LitElement, css, html, nothing } from 'lit';
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
  @state() private _isTyping = false;

  private _ws: WsManager | null = null;

  static styles = css`
    :host { 
      display: flex; flex-direction: column; height: 100%; box-sizing: border-box; 
      background: var(--bg-base);
    }
    
    .chat-header {
      padding: 16px 24px;
      border-bottom: 1px solid var(--border);
      display: flex; justify-content: space-between; align-items: center;
      flex-shrink: 0;
    }
    .header-title {
      font-size: 15px; font-weight: 600; color: var(--text-primary);
    }
    .cluster-status {
      display: flex; align-items: center; gap: 8px;
      font-size: 12px; color: var(--text-muted);
    }
    .status-badge {
      display: inline-flex; align-items: center; gap: 6px;
      padding: 4px 10px; border-radius: 99px;
      background: rgba(0, 212, 170, 0.1);
      color: var(--accent-secondary);
      font-weight: 600; font-size: 11px;
    }
    .pulse-dot {
      width: 6px; height: 6px; border-radius: 50%;
      background: var(--accent-secondary);
      box-shadow: 0 0 8px var(--accent-secondary);
      animation: pulse 2s infinite;
    }
    @keyframes pulse {
      0% { transform: scale(0.95); opacity: 0.9; }
      50% { transform: scale(1.05); opacity: 0.4; }
      100% { transform: scale(0.95); opacity: 0.9; }
    }

    .messages {
      flex: 1; overflow-y: auto; display: flex; flex-direction: column;
      gap: 20px; padding: 24px; scroll-behavior: smooth;
    }
    
    .msg-group {
      display: flex; flex-direction: column; gap: 6px;
      max-width: 80%;
    }
    .msg-group-user { align-self: flex-end; }
    .msg-group-assistant { align-self: flex-start; }
    .msg-group-system { align-self: center; max-width: 100%; }

    .msg-header {
      display: flex; align-items: center; gap: 8px;
      font-size: 11px; font-weight: 600; color: var(--text-muted);
      margin-bottom: 2px;
    }
    .msg-header-user { justify-content: flex-end; }

    .avatar {
      width: 28px; height: 28px; border-radius: 50%;
      background: var(--bg-elevated); border: 1px solid var(--border);
      display: flex; align-items: center; justify-content: center;
      color: var(--accent); font-size: 14px; flex-shrink: 0;
    }

    .bubble { 
      padding: 12px 16px; border-radius: 12px;
      font-size: 14px; line-height: 1.6; color: var(--text-primary);
      word-break: break-word; position: relative;
    }
    .bubble-user {
      background: var(--accent);
      border-radius: 18px 18px 4px 18px;
      box-shadow: 0 4px 12px rgba(108, 99, 255, 0.2);
    }
    .bubble-assistant {
      background: var(--bg-elevated);
      border: 1px solid var(--border);
      border-radius: 18px 18px 18px 4px;
    }
    .bubble-system {
      background: transparent; color: var(--text-muted); padding: 4px 12px;
      font-size: 11px; border: 1px solid var(--border); border-radius: 99px;
      text-transform: uppercase; letter-spacing: 0.05em;
    }
    
    .timestamp {
      font-size: 11px; color: var(--text-muted); margin-top: 2px;
    }
    .timestamp-user { text-align: right; }

    .typing {
      display: flex; gap: 4px; padding: 12px 16px;
      background: var(--bg-elevated); border-radius: 18px 18px 18px 4px;
      align-self: flex-start; border: 1px solid var(--border);
    }
    .dot {
      width: 6px; height: 6px; border-radius: 50%; background: var(--accent);
      opacity: 0.4;
    }
    .dot:nth-child(1) { animation: bounce 1.4s infinite ease-in-out; }
    .dot:nth-child(2) { animation: bounce 1.4s infinite ease-in-out -0.32s; }
    .dot:nth-child(3) { animation: bounce 1.4s infinite ease-in-out -0.16s; }
    @keyframes bounce {
      0%, 80%, 100% { transform: scale(0); }
      40% { transform: scale(1); opacity: 1; }
    }

    .input-wrapper {
      padding: 0 24px 24px; flex-shrink: 0;
    }
    .input-area {
      background: var(--bg-elevated); border: 1px solid var(--border);
      border-radius: 16px; padding: 8px 12px;
      display: flex; align-items: flex-end; gap: 12px;
      transition: var(--transition);
      box-shadow: 0 8px 24px rgba(0,0,0,0.2);
    }
    .input-area:focus-within {
      border-color: var(--accent);
      box-shadow: 0 0 0 3px var(--accent-glow), 0 8px 24px rgba(0,0,0,0.2);
    }
    
    textarea {
      flex: 1; background: transparent; border: none;
      color: var(--text-primary); padding: 10px 8px;
      font-size: 14px; font-family: inherit; line-height: 1.5;
      resize: none; max-height: 200px; min-height: 24px; outline: none;
    }
    .attach-btn {
       color: var(--text-muted); cursor: pointer; padding-bottom: 8px;
       transition: var(--transition);
    }
    .attach-btn:hover { color: var(--text-primary); }

    .send-btn {
      background: linear-gradient(135deg, var(--accent) 0%, var(--accent-secondary) 100%);
      border: none; border-radius: 12px; width: 38px; height: 38px;
      display: flex; align-items: center; justify-content: center;
      cursor: pointer; transition: transform 0.2s, brightness 0.2s;
      color: white; flex-shrink: 0; margin-bottom: 3px;
    }
    .send-btn:hover:not(:disabled) {
      brightness: 1.1; transform: translateY(-1px);
    }
    .send-btn:disabled {
      filter: grayscale(1); opacity: 0.4; cursor: not-allowed;
    }
    
    /* Markdown Styles */
    .bubble pre { background: rgba(0,0,0,0.2); padding: 12px; border-radius: 8px; margin: 8px 0; overflow-x: auto; }
    .bubble code { font-family: 'Fira Code', monospace; font-size: 0.9em; background: rgba(255,255,255,0.05); padding: 2px 4px; border-radius: 4px; }
    .bubble pre code { background: transparent; padding: 0; }
    .bubble p { margin-bottom: 8px; }
    .bubble p:last-child { margin-bottom: 0; }

    /* Approval Card - Custom Full Width Style */
    .approval-card {
       width: 100%; max-width: 100% !important; margin: 10px 0;
       border: 1px solid #f59e0b; background: rgba(245, 158, 11, 0.05);
       border-radius: 12px; overflow: hidden;
    }
    .approval-body { padding: 16px; border-bottom: 1px solid rgba(245, 158, 11, 0.2); }
    .approval-actions { display: flex; padding: 12px; gap: 10px; background: rgba(10, 11, 15, 0.5); }
    .btn-approve { flex: 1; height: 38px; background: #00d4aa; color: #0a0b0f; font-weight: 700; border: none; border-radius: 8px; cursor: pointer; }
    .btn-deny { border: 1px solid rgba(255,255,255,0.1); background: transparent; color: white; border-radius: 8px; padding: 0 16px; cursor: pointer; }
    textarea.deny-reason { width: 100%; background: var(--bg-base); border: 1px solid var(--border); border-radius: 8px; padding: 8px; margin-bottom: 8px; color: white; }
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
    const wsUrl = `ws://localhost:8080/ws/chat?apiKey=${encodeURIComponent(this.apiKey)}`;
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
    } else if (frame.type === 'message' && frame.role === 'assistant') {
      this._isTyping = false;
      const isApproval = frame.text.includes('⏸ Approval needed');
      this._messages = [...this._messages, {
        id: crypto.randomUUID(), role: 'assistant', text: frame.text, timestamp: Date.now(), isApprovalPrompt: isApproval
      }];
      if (isApproval) this._awaitingApproval = true;
      this._scrollToBottom();
    }
  }

  private _send() {
    const text = this._input.trim();
    if (!text || this._wsStatus !== 'open') return;
    this._messages = [...this._messages, { id: crypto.randomUUID(), role: 'user', text, timestamp: Date.now() }];
    this._ws?.send({ type: 'message', text });
    this._input = '';
    this._isTyping = true;
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
    setTimeout(() => {
      const el = this.shadowRoot?.querySelector('.messages');
      if (el) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
    }, 50);
  }
  
  private _renderMarkdown(text: string) {
    try {
      const template = document.createElement('div');
      template.innerHTML = marked.parse(text, { async: false }) as string;
      return template;
    } catch { return text; }
  }

  private _formatTime(ts: number) {
     return new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }

  render() {
    return html`
      <div class="messages">
        ${this._messages.map(m => m.role === 'system' ? html`
          <div class="msg-group msg-group-system">
             <div class="bubble bubble-system">${m.text}</div>
          </div>
        ` : html`
          <div class="msg-group msg-group-${m.role === 'user' ? 'user' : 'assistant'}">
             <div class="msg-header msg-header-${m.role === 'user' ? 'user' : 'assistant'}">
               ${m.role === 'assistant' ? html`<div class="avatar">⬡</div> ArcClaw Agent` : 'You'}
             </div>
             ${m.isApprovalPrompt ? html`
                <div class="approval-card">
                   <div class="approval-body">${this._renderMarkdown(m.text)}</div>
                   <div class="approval-actions">
                      <button class="btn-approve" @click="${this._sendApprove}">Approve Task</button>
                      <button class="btn-deny" @click="${this._sendDeny}">Deny</button>
                   </div>
                </div>
             ` : html`
                <div class="bubble bubble-${m.role}">
                  ${m.role === 'user' ? m.text : this._renderMarkdown(m.text)}
                </div>
             `}
             <div class="timestamp timestamp-${m.role}">${this._formatTime(m.timestamp)}</div>
          </div>
        `)}
        
        ${this._isTyping ? html`
          <div class="typing">
            <div class="dot"></div><div class="dot"></div><div class="dot"></div>
          </div>
        ` : nothing}
      </div>
      
      <div class="input-wrapper">
        <div class="input-area">
          <div class="attach-btn">📎</div>
          <textarea
            placeholder="Ask ArcClaw anything…"
            .value="${this._input}"
            ?disabled="${this._awaitingApproval}"
            rows="1"
            @input="${(e: Event) => {
               this._input = (e.target as HTMLTextAreaElement).value;
               const el = e.target as HTMLTextAreaElement;
               el.style.height = 'auto';
               el.style.height = el.scrollHeight + 'px';
            }}"
            @keydown="${(e: KeyboardEvent) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); this._send(); } }}"
          ></textarea>
          <button class="send-btn" ?disabled="${!this._input.trim() || this._wsStatus !== 'open' || this._awaitingApproval}" @click="${this._send}">
             <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><line x1="22" y1="2" x2="11" y2="13"></line><polygon points="22 2 15 22 11 13 2 9 22 2"></polygon></svg>
          </button>
        </div>
      </div>
    `;
  }
}
