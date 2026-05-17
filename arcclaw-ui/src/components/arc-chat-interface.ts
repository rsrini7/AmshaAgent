import { LitElement, css, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { WsManager } from '../api/websocket';
import { marked } from 'marked';
import { unsafeHTML } from 'lit/directives/unsafe-html.js';
import DOMPurify from 'dompurify';

interface ChatMessage {
  id: string;
  role: 'user' | 'assistant' | 'system';
  text: string;
  timestamp: number;
  isApprovalPrompt?: boolean;
  approvalData?: {
    tool: string;
    params: any;
  };
  historic?: boolean;
  targetSessionId?: string;
}

@customElement('arc-chat-interface')
export class ArcChatInterface extends LitElement {
  @property({ type: String }) apiKey = '';
  @property({ type: String }) sessionId = '';
  @property({ type: String }) platformId = '';

  @state() private _messages: ChatMessage[] = [];
  @state() private _input = '';
  @state() private _wsStatus: 'connecting' | 'open' | 'closed' | 'error' = 'closed';
  @state() private _awaitingApproval = false;
  @state() private _denyReason = '';
  @state() private _showDenyReason = false;
  @state() private _isTyping = false;
  @state() private _markdownCache = new Map<string, string>();

  private _ws: WsManager | null = null;
  private _connecting = false;
  private _intentionalClose = false;
  private _reconnectAttempts = 0;
  private _reconnectTimeoutId: any = null;
  private _wsToken = '';
  private _wsTokenFetchedAt = 0;
  private static readonly WS_TOKEN_TTL = 30_000; // reuse same token within 30s window

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
      display: flex; align-items: center; gap: 8px;
    }
    .status-badge {
       font-size: 10px; padding: 2px 8px; border-radius: 4px;
       text-transform: uppercase;
     }
    .status-open { background: rgba(0,212,170,0.1); color: var(--accent-secondary); }
    .status-connecting { background: rgba(245,158,11,0.1); color: #f59e0b; }
    .status-error { background: rgba(239,68,68,0.1); color: #ef4444; }
    .status-closed { background: rgba(255,255,255,0.05); color: var(--text-muted); }
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
      flex: 1; display: flex; flex-direction: column;
      padding: 24px; scroll-behavior: smooth;
      overflow-y: auto;
      position: relative;
      min-height: 0;
    }
    
    .msg-group {
      display: flex; flex-direction: column; gap: 2px;
      max-width: 80%;
      margin: 4px 0;
      flex: 0 0 auto;
    }
    .msg-group-user { align-self: flex-end; align-items: flex-end; }
    .msg-group-assistant { align-self: flex-start; align-items: flex-start; }
    .msg-group-system { align-self: center; max-width: 100%; align-items: center; margin: 12px 0; }

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
      padding: 8px 12px; border-radius: 12px;
      font-size: 13px; line-height: 1.4; color: var(--text-primary);
      word-break: break-word; position: relative;
      width: fit-content;
      max-width: 100%;
    }
    .bubble-user { white-space: pre-wrap; }
    .bubble-user {
      background: var(--accent);
      color: white;
      border-radius: 18px 18px 4px 18px;
      box-shadow: 0 4px 12px rgba(108, 99, 255, 0.2);
    }
    .bubble-assistant {
      background: var(--bg-elevated);
      border: 1px solid var(--border);
      border-radius: 18px 18px 18px 4px;
      box-shadow: 0 2px 8px rgba(0,0,0,0.1);
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
    .bubble p { margin-bottom: 4px; }
    .bubble p:last-child { margin-bottom: 0; }

    /* Approval Card - Custom Full Width Style */
    .approval-card {
       width: 100%; max-width: 600px !important; margin: 12px 0;
       border: 1px solid #f59e0b; background: rgba(245, 158, 11, 0.08);
       border-radius: 12px; overflow: hidden;
       box-shadow: 0 4px 20px rgba(0,0,0,0.3);
    }
    .approval-body { padding: 16px; border-bottom: 1px solid rgba(245, 158, 11, 0.2); }
    .approval-title { font-weight: 700; color: #f59e0b; display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
    .approval-tool { font-size: 13px; margin-bottom: 8px; }
    .approval-tool code { background: rgba(245, 158, 11, 0.2); padding: 2px 6px; border-radius: 4px; color: #fbbf24; }
    .approval-params-header { font-size: 11px; font-weight: 600; text-transform: uppercase; color: var(--text-muted); margin-bottom: 4px; }
    .approval-params { 
       background: rgba(0,0,0,0.3); padding: 12px; border-radius: 8px; 
       font-size: 12px; font-family: 'Fira Code', monospace; 
       margin: 0; overflow-x: auto; max-height: 240px; border: 1px solid rgba(255,255,255,0.05);
    }
    .approval-actions { display: flex; padding: 12px; gap: 10px; background: rgba(10, 11, 15, 0.5); }
    .btn-approve { flex: 1; height: 38px; background: #00d4aa; color: #0a0b0f; font-weight: 700; border: none; border-radius: 8px; cursor: pointer; }
    .btn-deny { border: 1px solid rgba(255,255,255,0.1); background: transparent; color: white; border-radius: 8px; padding: 0 16px; cursor: pointer; }
    textarea.deny-reason { width: 100%; background: var(--bg-base); border: 1px solid var(--border); border-radius: 8px; padding: 8px; margin-bottom: 8px; color: white; }
  `;

  updated(changedProperties: Map<string, unknown>) {
    const relevant = changedProperties.has('apiKey') || changedProperties.has('platformId');
    if (relevant && this.apiKey && this.platformId && !this._ws && !this._connecting && !this._intentionalClose) {
      this._connecting = true;
      this._connect();
    }
  }

  private async _loadHistory() {
    if (!this.sessionId) return;
    try {
      const res = await fetch(`/api/v1/chat/stream/history/${this.sessionId}`, {
        headers: {
          'X-ArcClaw-API-Key': this.apiKey
        }
      });
      if (!res.ok) return;
      const msgs = await res.json();
      this._messages = msgs
        .filter((m: any) => m.role === 'user' || m.role === 'assistant')
        .map((m: any) => ({
          id: crypto.randomUUID(),
          role: m.role,
          text: m.text,
          timestamp: m.timestamp || Date.now(),
          historic: true,
          isApprovalPrompt: m.role === 'assistant' && (m.text.includes('⏸') || m.text.includes('Approval')),
          actionId: m.actionId || null
        }));
      await this.updateComplete;
      this._scrollToBottom();
    } catch (e) {
      console.warn('ArcClaw: chat history fetch failed', e);
    }
  }
  
  disconnectedCallback() {
    super.disconnectedCallback();
    this._ws?.disconnect();
  }

  private async _connect() {
    if (this._intentionalClose) return;
    this._connecting = true;

    let token = this._wsToken && Date.now() - this._wsTokenFetchedAt < ArcChatInterface.WS_TOKEN_TTL
      ? this._wsToken : '';
    if (!token && this.apiKey) {
      try {
        const res = await fetch('/api/v1/chat/ws-token', {
          method: 'POST',
          headers: { 'X-ArcClaw-API-Key': this.apiKey, 'Content-Type': 'application/json' }
        });
        if (res.ok) {
          const data = await res.json();
          this._wsToken = data.token;
          this._wsTokenFetchedAt = Date.now();
          token = this._wsToken;
        }
      } catch (e) {
        console.warn('Failed to obtain WS token', e);
      }
    }

    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const host = window.location.host;
    const wsUrl = `${proto}://${host}/ws/chat?sessionId=${encodeURIComponent(this.platformId)}`;
    this._ws = new WsManager(
      wsUrl,
      (frame) => this._handleFrame(frame),
      (status) => {
        this._wsStatus = status;
        this.dispatchEvent(new CustomEvent('ws-status-change', {
          detail: { status },
          bubbles: true,
          composed: true
        }));
        if (status === 'open') {
          if (token) this._ws?.send({ type: 'AUTH', token });
          // Defer resetting backoff — WS must stay open for 5s first
          // to prevent rapid open/close loops from resetting the counter
          setTimeout(() => {
            if (this._wsStatus === 'open') {
              this._reconnectAttempts = 0;
            }
          }, 5000);
        }
        if (status === 'closed' || status === 'error') {
          this._ws = null;
          this._reconnectAttempts++;
          this._scheduleReconnect();
        }
      }
    );
    this._connecting = false;
    this._ws.connect();
  }

  private _scheduleReconnect() {
    if (this._intentionalClose || this._connecting) return;
    if (this._reconnectAttempts >= 10) {
      console.warn('ArcClaw: Too many reconnect failures, stopping');
      return;
    }

    // Exponential backoff with jitter (square at attempt so delay grows fast)
    const baseDelay = 1000;
    const maxDelay = 30000;
    const exponent = Math.min(this._reconnectAttempts, 5); // cap exponent to avoid overflow
    const delay = Math.min(maxDelay, baseDelay * Math.pow(2, exponent));
    const jitter = delay * 0.2 * (Math.random() * 2 - 1);
    const finalDelay = Math.max(baseDelay, delay + jitter);

    console.log(`ArcClaw: Reconnecting in ${Math.round(finalDelay)}ms (attempt ${this._reconnectAttempts})`);

    if (this._reconnectTimeoutId) clearTimeout(this._reconnectTimeoutId);
    this._reconnectTimeoutId = setTimeout(() => {
        this._reconnectTimeoutId = null;
        if (this._connecting || this._ws) return;
        this._connect();
    }, finalDelay);
  }

  private _handleFrame(frame: any) {
    if (frame.type === 'connected') {
      this._wsStatus = 'open';
      this._connecting = false;
      this._isTyping = false;
      
      // We now have the server-assigned internal session ID
      if (frame.sessionId) {
        if (frame.sessionId !== this.sessionId) {
          this.sessionId = frame.sessionId;
          // Dispatch to parent shell to update SSE subscriptions
          this.dispatchEvent(new CustomEvent('session-ready', { detail: { sessionId: frame.sessionId } }));
        }
        // Always reload history on connect/reconnect (handles page refresh)
        this._loadHistory();
      }
    } else if (frame.type === 'session_terminated') {
      this._intentionalClose = true;
      this._ws?.disconnect();
      this._wsStatus = 'closed';
    } else if (frame.type === 'message' && frame.role === 'assistant') {
      this._isTyping = false;
      this._awaitingApproval = false;
      this._showDenyReason = false;
      
      const isApproval = frame.approval || frame.text.includes('⏸');
      
      this._messages = [...this._messages, {
        id: crypto.randomUUID(), 
        role: 'assistant', 
        text: frame.text, 
        timestamp: frame.timestamp || Date.now(), 
        isApprovalPrompt: !!isApproval,
        approvalData: frame.approval || null,
        targetSessionId: frame.sessionId || null
      }];
      if (isApproval) this._awaitingApproval = true;
      // Scroll to bottom after new message
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
  
  private _sendApprove(msg: ChatMessage) {
     const cmd = msg.targetSessionId ? `/approve ${msg.targetSessionId}` : '/approve';
     this._ws?.send({ type: 'message', text: cmd });
     this._awaitingApproval = false;
     this._showDenyReason = false;
     this._messages = [...this._messages, { id: crypto.randomUUID(), role: 'user', text: cmd, timestamp: Date.now() }];
     this._scrollToBottom();
  }
  
  private _sendDeny(msg: ChatMessage) {
    if (!this._showDenyReason) {
      this._showDenyReason = true;
      return;
    }
    let cmd = msg.targetSessionId ? `/deny ${msg.targetSessionId}` : '/deny';
    if (this._denyReason.trim()) {
        cmd = msg.targetSessionId 
            ? `/deny ${msg.targetSessionId} ${this._denyReason.trim()}`
            : `/deny ${this._denyReason.trim()}`;
    }
    
    this._ws?.send({ type: 'message', text: cmd });
    this._awaitingApproval = false;
    this._showDenyReason = false;
    this._denyReason = '';
    this._messages = [...this._messages, { id: crypto.randomUUID(), role: 'user', text: cmd, timestamp: Date.now() }];
    this._scrollToBottom();
  }

  private _scrollToBottom() {
    this.updateComplete.then(() => {
      requestAnimationFrame(() => {
        const el = this.shadowRoot?.querySelector('.messages');
        if (el) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
      });
    });
  }
  
  private _renderMarkdown(messageId: string, text: string) {
    if (!text) return nothing;
    
    // SR-14: Cache rendered markdown
    if (this._markdownCache.has(messageId)) {
      return unsafeHTML(this._markdownCache.get(messageId)!);
    }

    const rawHtml = marked.parse(text, { breaks: true }) as string;
    const cleanHtml = DOMPurify.sanitize(rawHtml);
    
    // Update cache (limit size to 200 items)
    if (this._markdownCache.size > 200) {
      const firstKey = this._markdownCache.keys().next().value;
      if (firstKey !== undefined) this._markdownCache.delete(firstKey);
    }
    this._markdownCache.set(messageId, cleanHtml);
    
    return unsafeHTML(cleanHtml);
  }

  private _formatTime(ts: number) {
     return new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }

  public reconnect() {
    if (this._reconnectTimeoutId) {
      clearTimeout(this._reconnectTimeoutId);
      this._reconnectTimeoutId = null;
    }
    this._reconnectAttempts = 0;
    this._ws = null; 
    this._intentionalClose = false; 
    this._connecting = true;
    this._connect();
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
               <div class="bubble bubble-${m.role === 'user' ? 'user' : 'assistant'}">${m.role === 'user' ? m.text : this._renderMarkdown(m.id, m.text)}${m.isApprovalPrompt && this._awaitingApproval ? html`<div class="approval-card"><div class="approval-body"><div class="approval-title"><span>⏸</span> Action Requires Approval</div>${m.approvalData ? html`<div class="approval-tool">Tool: <code>${m.approvalData.tool}</code></div><div class="approval-params-header">Parameters:</div><pre class="approval-params"><code>${typeof m.approvalData.params === 'string' ? m.approvalData.params : JSON.stringify(m.approvalData.params, null, 2)}</code></pre>` : html`<p style="margin: 4px 0 0 0; font-size: 12px; color: var(--text-muted);">This session has paused to wait for your permission.</p>`}</div><div class="approval-actions">${this._showDenyReason ? html`<textarea class="deny-reason" .value=${this._denyReason} @input=${(e: any) => this._denyReason = e.target.value} placeholder="Reason for denial (optional)..."></textarea>` : nothing}<button class="btn-approve" @click=${() => this._sendApprove(m)}>Approve</button><button class="btn-deny" @click=${() => this._sendDeny(m)}>${this._showDenyReason ? 'Send Feedback' : 'Deny'}</button></div></div>` : nothing}</div>
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
            aria-label="Message ArcClaw"
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
          <button aria-label="Send message" class="send-btn" ?disabled="${!this._input.trim() || this._wsStatus !== 'open' || this._awaitingApproval}" @click="${this._send}">
             <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><line x1="22" y1="2" x2="11" y2="13"></line><polygon points="22 2 15 22 11 13 2 9 22 2"></polygon></svg>
          </button>
        </div>
      </div>
    `;
  }
}
