import { LitElement, css, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { consume } from '@lit/context';
import { arcContext, ArcAppContext } from '../context/arc-context';
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
  audio?: {
    mimeType: string;
    fileName: string;
    data: string;
    url?: string;
  };
}

interface ProgressUpdate {
  phase: string;
  detail: string;
}

const HALTED_MESSAGE_PATTERNS = [
  /session halted by user/i,
  /halted by user/i,
  /loop exceeded/i,
  /budget exceeded/i,
  /timed out/i,
  /session halted/i,
];

@customElement('arc-chat-interface')
export class ArcChatInterface extends LitElement {
  @consume({ context: arcContext, subscribe: true })
  @state()
  private _appContext!: ArcAppContext;

  @property({ type: String }) platformId = '';

  @state() private _messages: ChatMessage[] = [];
  @state() private _input = '';
  @state() private _audioAttachment: File | null = null;
  @state() private _attachmentError = '';
  @state() private _encodingAttachment = false;
  @state() private _isRecording = false;
  @state() private _autoPlaySpeech = false;
  @state() private _wsStatus: 'connecting' | 'open' | 'closed' | 'error' = 'closed';
  @state() private _awaitingApproval = false;
  @state() private _denyReason = '';
  @state() private _showDenyReason = false;
  @state() private _isTyping = false;
  @state() private _sessionHalted = false;
  @state() private _progressPhase = '';
  @state() private _progressDetail = '';
  @state() private _progressTrail: ProgressUpdate[] = [];
  @state() private _markdownCache = new Map<string, string>();

  private _ws: WsManager | null = null;
  private _connecting = false;
  private _intentionalClose = false;
  private _reconnectAttempts = 0;
  private _reconnectTimeoutId: any = null;
  private _wsToken = '';
  private _wsTokenFetchedAt = 0;
  private _lastConnectAt = 0;
  private _isReconnecting = false;
  private _boundProgress = ((e: Event) => this._onProgress(e as CustomEvent<ProgressUpdate>)) as EventListener;
  private static readonly WS_TOKEN_TTL = 30_000;
  private static readonly MIN_CONNECT_INTERVAL = 5000;
  private static readonly MAX_AUDIO_UPLOAD_BYTES = 25 * 1024 * 1024;
  private static readonly SUPPORTED_AUDIO_TYPES = new Set([
    'audio/mpeg', 'audio/mp3', 'audio/wav', 'audio/x-wav', 'audio/wave', 'audio/ogg', 'audio/webm', 'video/webm',
  ]);
  private static readonly AUDIO_EXT_TO_MIME: Record<string, string> = {
    '.mp3': 'audio/mpeg', '.wav': 'audio/wav', '.ogg': 'audio/ogg', '.webm': 'audio/webm',
  };

  private _mediaRecorder: MediaRecorder | null = null;
  private _recordedChunks: Blob[] = [];
  private _recordingStream: MediaStream | null = null;

  static styles = css`
    :host { 
      display: flex; flex-direction: column;
      box-sizing: border-box; 
      background: var(--bg-base);
      min-height: 0;
    }

    .chat-shell {
      flex: 1;
      min-height: 0;
      display: flex;
      flex-direction: column;
    }

    .progress-shell {
      position: sticky;
      top: 0;
      z-index: 30;
      flex-shrink: 0;
      padding: 16px 24px 12px;
      background: linear-gradient(180deg, color-mix(in srgb, var(--bg-base) 96%, transparent), color-mix(in srgb, var(--bg-base) 88%, transparent));
      backdrop-filter: blur(10px);
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
    .messages::-webkit-scrollbar { width: 6px; }
    .messages::-webkit-scrollbar-track { background: transparent; }
    .messages::-webkit-scrollbar-thumb { background: var(--border); border-radius: 999px; }
    .messages::-webkit-scrollbar-thumb:hover { background: var(--text-faint); }
    
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
    .progress-panel {
      width: 100%;
      min-height: 96px;
      padding: 14px 16px;
      margin: 0;
      border: 1px solid color-mix(in srgb, var(--accent-secondary) 28%, var(--border));
      border-radius: 18px 18px 18px 4px;
      background:
        linear-gradient(
          180deg,
          color-mix(in srgb, var(--bg-elevated) 90%, transparent),
          color-mix(in srgb, var(--accent-secondary) 8%, transparent)
        );
      box-shadow: 0 10px 28px rgba(0, 0, 0, 0.18);
      position: relative;
      overflow: hidden;
    }
    .progress-panel::before {
      content: '';
      position: absolute;
      inset: 0 auto auto 0;
      width: 160px;
      height: 2px;
      background: linear-gradient(90deg, transparent, var(--accent-secondary), transparent);
      animation: progress-sheen 1.8s linear infinite;
    }
    @keyframes progress-sheen {
      0% { transform: translateX(-120%); opacity: 0.35; }
      100% { transform: translateX(calc(100vw + 120%)); opacity: 0.9; }
    }
    .progress-head {
      display: flex;
      align-items: center;
      gap: 10px;
      min-width: 0;
    }
    .progress-copy {
      min-width: 0;
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    .progress-kicker {
      font-size: 10px;
      letter-spacing: 0.08em;
      text-transform: uppercase;
      color: var(--text-muted);
    }
    .progress-phase {
      font-size: 14px;
      font-weight: 700;
      color: var(--text-primary);
    }
    .progress-detail {
      margin-top: 8px;
      font-size: 13px;
      line-height: 1.45;
      color: var(--text-muted);
      max-width: 72ch;
      min-height: 1.45em;
    }
    .progress-trail {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
      margin-top: 12px;
      max-width: 100%;
    }
    .progress-chip {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 4px 10px;
      border-radius: 999px;
      font-size: 11px;
      color: var(--text-muted);
      border: 1px solid var(--border);
      background: color-mix(in srgb, var(--bg-base) 80%, transparent);
    }
    .progress-chip-current {
      color: var(--text-primary);
      border-color: color-mix(in srgb, var(--accent-secondary) 40%, var(--border));
      background: color-mix(in srgb, var(--accent-secondary) 14%, var(--bg-base));
    }
    .progress-bar {
      margin-top: 12px;
      height: 2px;
      border-radius: 999px;
      overflow: hidden;
      background: color-mix(in srgb, var(--border) 40%, transparent);
    }
    .progress-bar > span {
      display: block;
      width: 42%;
      height: 100%;
      border-radius: inherit;
      background: linear-gradient(90deg, transparent, var(--accent-secondary), transparent);
      animation: progress-bar-move 1.5s ease-in-out infinite;
    }
    .halt-actions {
      display: flex;
      justify-content: flex-end;
      margin-top: 12px;
    }
    .new-session-btn {
      border: 1px solid color-mix(in srgb, var(--accent-secondary) 35%, var(--border));
      background: color-mix(in srgb, var(--accent-secondary) 12%, var(--bg-base));
      color: var(--text-primary);
      border-radius: 999px;
      padding: 8px 14px;
      font-size: 12px;
      font-weight: 700;
      cursor: pointer;
      transition: transform 0.15s ease, border-color 0.15s ease, background 0.15s ease;
    }
    .new-session-btn:hover {
      transform: translateY(-1px);
      border-color: color-mix(in srgb, var(--accent-secondary) 55%, var(--border));
      background: color-mix(in srgb, var(--accent-secondary) 18%, var(--bg-base));
    }
    @keyframes progress-bar-move {
      0% { transform: translateX(-120%); }
      100% { transform: translateX(260%); }
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
    .attachment-input { display: none; }
    .attachment-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 10px;
      margin-bottom: 8px;
      padding: 7px 10px;
      border: 1px solid var(--border);
      border-radius: 10px;
      background: var(--bg-elevated);
      color: var(--text-muted);
      font-size: 12px;
    }
    .attachment-row button {
      border: 0;
      background: transparent;
      color: var(--text-muted);
      cursor: pointer;
      font-size: 16px;
    }
    .attachment-error {
      margin-bottom: 8px;
      color: #ef4444;
      font-size: 12px;
    }
    .mic-btn {
      color: var(--text-muted); cursor: pointer; padding-bottom: 8px;
      transition: var(--transition); background: transparent; border: 0;
      font-size: 16px; line-height: 1;
    }
    .mic-btn:hover { color: var(--text-primary); }
    .mic-btn.recording { color: #ef4444; animation: pulse 1s infinite; }
    .tts-btn {
      color: var(--text-muted); cursor: pointer; padding-bottom: 8px;
      transition: var(--transition); background: transparent; border: 0;
      font-size: 16px; line-height: 1;
    }
    .tts-btn:hover { color: var(--text-primary); }
    .tts-btn.active { color: var(--accent-secondary); }
    .assistant-audio-player, .user-audio-player {
      margin-top: 8px;
      display: flex;
      flex-direction: column;
      gap: 6px;
      width: 100%;
      min-width: 260px;
      max-width: 340px;
    }
    .audio-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
    }
    .audio-badge {
      font-size: 11px;
      font-weight: 600;
      letter-spacing: 0.04em;
      opacity: 0.9;
    }
    .replay-btn {
      background: color-mix(in srgb, var(--accent) 15%, transparent);
      border: 1px solid color-mix(in srgb, var(--accent) 30%, var(--border));
      color: var(--text-primary);
      border-radius: 6px;
      padding: 3px 8px;
      font-size: 11px;
      font-weight: 600;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 4px;
      transition: background 0.15s ease, transform 0.15s ease;
    }
    .replay-btn:hover {
      transform: translateY(-1px);
      background: color-mix(in srgb, var(--accent) 25%, transparent);
    }
    .bubble-user .replay-btn {
      background: rgba(255, 255, 255, 0.2);
      border: 1px solid rgba(255, 255, 255, 0.35);
      color: white;
    }
    .bubble-user .replay-btn:hover {
      background: rgba(255, 255, 255, 0.3);
    }
    .assistant-audio-player audio, .user-audio-player audio {
      height: 34px;
      width: 100%;
      border-radius: 8px;
      outline: none;
    }

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
    .bubble pre {
      background: rgba(0,0,0,0.2); padding: 12px; border-radius: 8px;
      margin: 8px 0; overflow-x: auto; white-space: pre-wrap; word-break: break-word;
      max-width: 100%; font-size: 13px; line-height: 1.5;
    }
    .bubble pre::-webkit-scrollbar { height: 4px; }
    .bubble pre::-webkit-scrollbar-track { background: transparent; }
    .bubble pre::-webkit-scrollbar-thumb { background: var(--border); border-radius: 999px; }
    .bubble code { font-family: 'Fira Code', monospace; font-size: 0.9em; background: rgba(255,255,255,0.05); padding: 2px 4px; border-radius: 4px; }
    .bubble pre code { background: transparent; padding: 0; font-size: inherit; }
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
    const relevant = changedProperties.has('_appContext') || changedProperties.has('platformId');
    if (relevant && (this._appContext?.platformId || this.platformId) && !this._ws && !this._connecting && !this._intentionalClose) {
      this._connecting = true;
      this._connect();
    }
  }

  private async _loadHistory(sessionId?: string) {
    const sid = sessionId || this._appContext?.sessionId;
    if (!sid) return;
    try {
      const headers: Record<string, string> = {};
      if (this._appContext?.apiKey && this._appContext.apiKey !== 'authenticated') {
        headers['X-Amsha-API-Key'] = this._appContext.apiKey;
      }
      const res = await fetch(`/api/v1/chat/stream/history/${sid}`, {
        credentials: 'include',
        headers
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
      this._sessionHalted = [...this._messages]
        .reverse()
        .some((m) => m.role === 'assistant' && HALTED_MESSAGE_PATTERNS.some((pattern) => pattern.test(m.text)));
      await this.updateComplete;
      this._scrollToBottom();
    } catch (e) {
      console.warn('Amsha Agent: chat history fetch failed', e);
    }
  }

  connectedCallback() {
    super.connectedCallback();
    window.addEventListener('amsha-agent-progress', this._boundProgress);
  }
  
  disconnectedCallback() {
    super.disconnectedCallback();
    window.removeEventListener('amsha-agent-progress', this._boundProgress);
    if (this._reconnectTimeoutId) {
      clearTimeout(this._reconnectTimeoutId);
      this._reconnectTimeoutId = null;
    }
    if (this._isRecording) {
      this._stopRecording();
    }
    this._recordingStream?.getTracks().forEach((track) => track.stop());
    this._markdownCache.clear();
    this._resetProgress();
    this._ws?.disconnect();
  }

  private _onProgress(event: CustomEvent<ProgressUpdate>) {
    const phase = event.detail?.phase?.trim();
    const detail = event.detail?.detail?.trim() || '';
    if (!phase && !detail) return;

    this._progressPhase = phase || this._progressPhase || 'Working';
    this._progressDetail = detail;

    const next = { phase: this._progressPhase, detail: this._progressDetail };
    const last = this._progressTrail[this._progressTrail.length - 1];
    if (!last || last.phase !== next.phase || last.detail !== next.detail) {
      this._progressTrail = [...this._progressTrail, next].slice(-4);
    }

    this._isTyping = true;
    this._scrollToBottom();
  }

  private _resetProgress() {
    this._progressPhase = '';
    this._progressDetail = '';
    this._progressTrail = [];
  }

  private async _connect() {
    const now = Date.now();
    if (!this._isReconnecting && now - this._lastConnectAt < ArcChatInterface.MIN_CONNECT_INTERVAL) return;
    this._isReconnecting = false;
    this._lastConnectAt = now;
    if (this._intentionalClose) return;
    this._connecting = true;

    let token = this._wsToken && Date.now() - this._wsTokenFetchedAt < ArcChatInterface.WS_TOKEN_TTL
      ? this._wsToken : '';
    if (!token && this._appContext?.apiKey) {
      try {
        const wsHeaders: Record<string, string> = {
          'Content-Type': 'application/json'
        };
        if (this._appContext.apiKey !== 'authenticated') {
          wsHeaders['X-Amsha-API-Key'] = this._appContext.apiKey;
        }
        const res = await fetch('/api/v1/chat/ws-token', {
          method: 'POST',
          credentials: 'include',
          headers: wsHeaders
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
    const wsUrl = `${proto}://${host}/ws/chat?sessionId=${encodeURIComponent(this.platformId || this._appContext?.platformId || '')}`;
    const manager = new WsManager(
      wsUrl,
      (frame) => this._handleFrame(frame),
      (status) => {
        if (this._ws !== manager) return; // stale connection
        this._wsStatus = status;
        window.dispatchEvent(new CustomEvent('ws-status-change', {
          detail: { status }
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
    this._ws = manager;
    this._connecting = false;
    this._ws.connect();
  }

  private _scheduleReconnect() {
    if (this._intentionalClose || this._connecting) return;
    if (this._reconnectAttempts >= 10) {
      console.warn('Amsha Agent: Too many reconnect failures, stopping');
      return;
    }

    // Exponential backoff with jitter (square at attempt so delay grows fast)
    const baseDelay = 1000;
    const maxDelay = 30000;
    const exponent = Math.min(this._reconnectAttempts, 5); // cap exponent to avoid overflow
    const delay = Math.min(maxDelay, baseDelay * Math.pow(2, exponent));
    const jitter = delay * 0.2 * (Math.random() * 2 - 1);
    const finalDelay = Math.max(baseDelay, delay + jitter);

    console.log(`Amsha Agent: Reconnecting in ${Math.round(finalDelay)}ms (attempt ${this._reconnectAttempts})`);

    if (this._reconnectTimeoutId) clearTimeout(this._reconnectTimeoutId);
    this._reconnectTimeoutId = setTimeout(() => {
        this._reconnectTimeoutId = null;
        if (this._connecting || this._ws) return;
        this._isReconnecting = true;
        this._connect();
    }, finalDelay);
  }

  private _handleFrame(frame: any) {
    if (frame.type === 'connected') {
      this._wsStatus = 'open';
      this._connecting = false;
      this._isTyping = false;
      this._resetProgress();
      
      // We now have the server-assigned internal session ID
      if (frame.sessionId) {
        if (frame.sessionId !== (this._appContext?.sessionId || '')) {
          // Dispatch to parent shell to update SSE subscriptions
          window.dispatchEvent(new CustomEvent('session-ready', {
            detail: { sessionId: frame.sessionId }
          }));
        }
        // Always reload history on connect/reconnect (handles page refresh)
        this._loadHistory(frame.sessionId || this._appContext?.sessionId);
      }
    } else if (frame.type === 'session_terminated') {
      if (this._intentionalClose) return;
      this._intentionalClose = true;
      if (this._ws) {
        this._ws.disconnect();
        this._ws = null;
      }
      this._wsStatus = 'closed';
      this._sessionHalted = true;
      this._resetProgress();
    } else if (frame.type === 'message' && frame.role === 'assistant') {
      const isApproval = frame.approval || (frame.text && frame.text.includes('⏸'));
      this._isTyping = false;
      this._showDenyReason = false;

      const audioPayload = frame.audio && frame.audio.data
        ? { ...frame.audio, url: this._base64ToBlobUrl(frame.audio.data, frame.audio.mimeType) }
        : undefined;

      this._messages = [...this._messages, {
        id: crypto.randomUUID(),
        role: 'assistant',
        text: frame.text || '',
        timestamp: frame.timestamp || Date.now(),
        isApprovalPrompt: !!isApproval,
        approvalData: frame.approval || null,
        targetSessionId: frame.sessionId || null,
        audio: audioPayload
      }];
      this._awaitingApproval = !!isApproval;
      if (frame.text && HALTED_MESSAGE_PATTERNS.some((pattern) => pattern.test(frame.text))) {
        this._sessionHalted = true;
      } else if (!isApproval) {
        this._sessionHalted = false;
      }
      if (isApproval) {
        this._progressPhase = 'Waiting for approval';
        this._progressDetail = 'Approve or deny the tool request';
        this._progressTrail = [...this._progressTrail, { phase: this._progressPhase, detail: this._progressDetail }].slice(-4);
      } else {
        this._resetProgress();
      }

      if (audioPayload && this._autoPlaySpeech) {
        try {
          const snd = new Audio(audioPayload.url || `data:${audioPayload.mimeType};base64,${audioPayload.data}`);
          snd.play().catch(() => {});
        } catch (_) {}
      }

      // Scroll to bottom after new message
      this._scrollToBottom();
    }
  }

  private async _send() {
    const text = this._input.trim();
    const attachment = this._audioAttachment;
    if ((!text && !attachment) || this._wsStatus !== 'open' || this._encodingAttachment) {
      return;
    }
    if (attachment && !this._isSupportedAudioFile(attachment)) {
      this._attachmentError = 'Supported audio formats are MP3, WAV, OGG, and WebM (max 25 MiB)';
      return;
    }
    let outbound: { type: 'message'; text: string; attachment?: { mimeType: string; fileName: string; data: string } } = {
      type: 'message',
      text,
    };
    let displayText = text;
    let userAudioPayload: { mimeType: string; fileName: string; data: string; url?: string } | undefined;
    if (attachment) {
      this._encodingAttachment = true;
      try {
        const data = await this._fileToBase64(attachment);
        const mimeType = this._resolveAttachmentMimeType(attachment);
        if (!mimeType) {
          this._encodingAttachment = false;
          return;
        }
        outbound.attachment = { mimeType: mimeType as 'audio/mpeg' | 'audio/wav' | 'audio/ogg' | 'audio/webm', fileName: attachment.name, data };
        let blobUrl: string | undefined;
        try {
          blobUrl = URL.createObjectURL(attachment);
        } catch (_) {}
        userAudioPayload = { mimeType, fileName: attachment.name, data, url: blobUrl };
        displayText = text ? `${text} [${attachment.name}]` : `[${attachment.name}]`;
      } catch (_) {
        this._attachmentError = 'Failed to encode audio attachment';
        this._encodingAttachment = false;
        return;
      }
      this._encodingAttachment = false;
    }
    this._messages = [
      ...this._messages,
      { id: crypto.randomUUID(), role: 'user', text: displayText, timestamp: Date.now(), audio: userAudioPayload },
    ];
    this._ws?.send(outbound as unknown as import('../api/types').WsOutboundMessage);
    this._input = '';
    this._audioAttachment = null;
    this._attachmentError = '';
    const fileInput = this.shadowRoot?.querySelector('.attachment-input') as HTMLInputElement | null;
    if (fileInput) {
      fileInput.value = '';
    }
    this._progressPhase = 'Thinking';
    this._progressDetail = 'Preparing the first response';
    this._progressTrail = [{ phase: this._progressPhase, detail: this._progressDetail }];
    this._sessionHalted = false;
    this._isTyping = true;
    this._scrollToBottom();
  }

  private _isSupportedAudioFile(file: File): boolean {
    if (file.size === 0 || file.size > ArcChatInterface.MAX_AUDIO_UPLOAD_BYTES) {
      return false;
    }
    const mime = (file.type || '').split(';')[0].trim().toLowerCase();
    if (ArcChatInterface.SUPPORTED_AUDIO_TYPES.has(mime)) {
      return true;
    }
    const ext = `.${file.name.split('.').pop()?.toLowerCase() ?? ''}`;
    return ext in ArcChatInterface.AUDIO_EXT_TO_MIME;
  }

  private _resolveAttachmentMimeType(file: File): string {
    const mime = (file.type || '').split(';')[0].trim().toLowerCase();
    if (mime === 'audio/mp3') {
      return 'audio/mpeg';
    }
    if (mime === 'audio/x-wav' || mime === 'audio/wave') {
      return 'audio/wav';
    }
    if (mime === 'video/webm') {
      return 'audio/webm';
    }
    if (ArcChatInterface.SUPPORTED_AUDIO_TYPES.has(mime)) {
      return mime;
    }
    const ext = `.${file.name.split('.').pop()?.toLowerCase() ?? ''}`;
    const byExt = ArcChatInterface.AUDIO_EXT_TO_MIME[ext];
    if (byExt) {
      return byExt;
    }
    this._attachmentError = `Unsupported file type: ${ext || 'unknown'} — use MP3, WAV, OGG, or WebM`;
    return '';
  }

  private _fileToBase64(file: File): Promise<string> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => {
        const result = reader.result as string;
        const comma = result.indexOf(',');
        resolve(comma >= 0 ? result.slice(comma + 1) : result);
      };
      reader.onerror = () => reject(reader.error);
      reader.readAsDataURL(file);
    });
  }

  private _base64ToBlobUrl(base64: string, mimeType: string): string {
    try {
      const byteCharacters = atob(base64);
      const byteNumbers = new Uint8Array(byteCharacters.length);
      for (let i = 0; i < byteCharacters.length; i++) {
        byteNumbers[i] = byteCharacters.charCodeAt(i);
      }
      const blob = new Blob([byteNumbers], { type: mimeType });
      return URL.createObjectURL(blob);
    } catch (_) {
      return `data:${mimeType};base64,${base64}`;
    }
  }

  private _replayAudio(audio: { url?: string; mimeType: string; data: string }): void {
    try {
      const src = audio.url || `data:${audio.mimeType};base64,${audio.data}`;
      const snd = new Audio(src);
      snd.play().catch(() => {});
    } catch (_) {}
  }

  private _onAudioFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    if (!file) {
      return;
    }
    if (!this._isSupportedAudioFile(file)) {
      this._attachmentError = 'Supported audio formats are MP3, WAV, OGG, and WebM (max 25 MiB)';
      input.value = '';
      return;
    }
    this._attachmentError = '';
    this._audioAttachment = file;
  }

  private _clearAttachment(): void {
    this._audioAttachment = null;
    this._attachmentError = '';
    const fileInput = this.shadowRoot?.querySelector('.attachment-input') as HTMLInputElement | null;
    if (fileInput) {
      fileInput.value = '';
    }
  }

  private _triggerFilePicker(): void {
    const fileInput = this.shadowRoot?.querySelector('.attachment-input') as HTMLInputElement | null;
    fileInput?.click();
  }

  private async _toggleRecording(): Promise<void> {
    if (this._isRecording) {
      this._stopRecording();
    } else {
      await this._startRecording();
    }
  }

  private async _startRecording(): Promise<void> {
    if (this._isRecording) {
      return;
    }
    if (!navigator.mediaDevices?.getUserMedia) {
      this._attachmentError = 'Microphone recording is not supported in this browser';
      return;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
      });
      this._recordingStream = stream;
      this._recordedChunks = [];
      const preferred = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus', 'audio/ogg'];
      let mimeType = '';
      for (const candidate of preferred) {
        if (MediaRecorder.isTypeSupported(candidate)) {
          mimeType = candidate;
          break;
        }
      }
      if (!mimeType && typeof MediaRecorder.isTypeSupported === 'function') {
        this._attachmentError = 'Voice recording in WebM or OGG is not supported in this browser';
        stream.getTracks().forEach((track) => track.stop());
        this._recordingStream = null;
        return;
      }
      const recorder = mimeType ? new MediaRecorder(stream, { mimeType }) : new MediaRecorder(stream);
      recorder.ondataavailable = (event: BlobEvent) => {
        if (event.data.size > 0) {
          this._recordedChunks.push(event.data);
        }
      };
      recorder.onstop = () => {
        const blobMime = recorder.mimeType || mimeType || 'audio/webm';
        const normalized = blobMime.split(';')[0].trim().toLowerCase();
        if (normalized !== 'audio/webm' && normalized !== 'audio/ogg') {
          this._attachmentError = `Recorded audio format (${normalized || 'unknown'}) is not supported — use WebM or OGG`;
        } else {
          const blob = new Blob(this._recordedChunks, { type: normalized });
          if (blob.size === 0 || blob.size > ArcChatInterface.MAX_AUDIO_UPLOAD_BYTES) {
            this._attachmentError = 'Recording must be between 1 byte and 25 MiB';
          } else {
            const fileName = normalized === 'audio/ogg' ? 'recording.ogg' : 'recording.webm';
            this._audioAttachment = new File([blob], fileName, { type: normalized });
            this._attachmentError = '';
            const fileInput = this.shadowRoot?.querySelector('.attachment-input') as HTMLInputElement | null;
            if (fileInput) {
              fileInput.value = '';
            }
          }
        }
        this._recordedChunks = [];
        this._recordingStream?.getTracks().forEach((track) => track.stop());
        this._recordingStream = null;
        this._mediaRecorder = null;
        this._isRecording = false;
      };
      this._mediaRecorder = recorder;
      recorder.start(100);
      this._isRecording = true;
      this._attachmentError = '';
      const fileInput = this.shadowRoot?.querySelector('.attachment-input') as HTMLInputElement | null;
      if (fileInput) {
        fileInput.value = '';
      }
    } catch (_) {
      this._attachmentError = 'Microphone access was denied or unavailable';
      this._recordingStream?.getTracks().forEach((track) => track.stop());
      this._recordingStream = null;
    }
  }

  private _stopRecording(): void {
    if (!this._isRecording || !this._mediaRecorder) {
      return;
    }
    try {
      if (this._mediaRecorder.state !== 'inactive') {
        this._mediaRecorder.stop();
      } else {
        this._recordingStream?.getTracks().forEach((track) => track.stop());
        this._recordingStream = null;
        this._isRecording = false;
      }
    } catch (_) {
      this._recordingStream?.getTracks().forEach((track) => track.stop());
      this._recordingStream = null;
      this._mediaRecorder = null;
      this._isRecording = false;
    }
  }

  public startNewSession(platformId: string) {
    if (this._reconnectTimeoutId) {
      clearTimeout(this._reconnectTimeoutId);
      this._reconnectTimeoutId = null;
    }

    this._intentionalClose = true;
    if (this._ws) {
      this._ws.disconnect();
      this._ws = null;
    }

    this._messages = [];
    this._input = '';
    this._awaitingApproval = false;
    this._denyReason = '';
    this._showDenyReason = false;
    this._isTyping = false;
    this._sessionHalted = false;
    this._resetProgress();

    this.platformId = platformId;
    sessionStorage.setItem('amsha-platform-id', platformId);
    this._intentionalClose = false;
    this._isReconnecting = true;
    this._connecting = true;
    this._connect();
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
    const cleanHtml = DOMPurify.sanitize(rawHtml, {
      ALLOWED_TAGS: ['p', 'br', 'strong', 'em', 'code', 'pre', 'ul', 'ol', 'li', 'a', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'blockquote', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'span'],
      ALLOWED_ATTR: ['href', 'title', 'target', 'class'],
    });
    
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
    if (this._connecting) return;
    if (this._reconnectTimeoutId) {
      clearTimeout(this._reconnectTimeoutId);
      this._reconnectTimeoutId = null;
    }
    this._reconnectAttempts = 0;
    this._intentionalClose = false;
    if (this._ws) {
      this._ws.disconnect();
      this._ws = null;
    }
    this._isReconnecting = true;
    this._connecting = true;
    this._connect();
  }

  render() {
    const showProgress = this._isTyping || this._awaitingApproval || this._progressTrail.length > 0;
    const phaseLabel = this._progressPhase || (this._awaitingApproval ? 'Waiting for approval' : 'Working');
    const detailLabel = this._progressDetail || (this._awaitingApproval ? 'Approve or deny the tool request' : 'Processing the request');

    return html`
      <div class="chat-shell">
        <div class="progress-shell">
          ${showProgress ? html`
            <div class="progress-panel">
              <div class="progress-head">
                <div class="pulse-dot"></div>
                <div class="progress-copy">
                  <div class="progress-kicker">Live progress</div>
                  <div class="progress-phase">${phaseLabel}</div>
                </div>
              </div>
              <div class="progress-detail">${detailLabel}</div>
              ${this._progressTrail.length > 1 ? html`
                <div class="progress-trail">
                  ${this._progressTrail.map((step, index) => html`
                    <span class="progress-chip ${index === this._progressTrail.length - 1 ? 'progress-chip-current' : ''}">
                      ${step.phase}
                    </span>
                  `)}
                </div>
              ` : nothing}
              <div class="progress-bar"><span></span></div>
              ${this._sessionHalted ? html`
                <div class="halt-actions">
                  <button class="new-session-btn" @click=${() => this.startNewSession(crypto.randomUUID())}>New Session</button>
                </div>
              ` : nothing}
            </div>
          ` : nothing}
        </div>

        <div class="messages">
          ${this._messages.map(m => m.role === 'system' ? html`
            <div class="msg-group msg-group-system">
               <div class="bubble bubble-system">${m.text}</div>
            </div>
          ` : html`
            <div class="msg-group msg-group-${m.role === 'user' ? 'user' : 'assistant'}">
               <div class="msg-header msg-header-${m.role === 'user' ? 'user' : 'assistant'}">
                 ${m.role === 'assistant' ? html`<div class="avatar">⬡</div> Amsha Agent` : 'You'}
               </div>
               <div class="bubble bubble-${m.role === 'user' ? 'user' : 'assistant'}">${m.role === 'user' ? html`<div>${m.text}</div>${m.audio ? html`<div class="user-audio-player"><div class="audio-header"><span class="audio-badge">🎤 Voice Message</span><button class="replay-btn" @click=${() => this._replayAudio(m.audio!)} title="Replay voice audio">🔄 Replay</button></div><audio controls preload="metadata" src="${m.audio.url || `data:${m.audio.mimeType};base64,${m.audio.data}`}"></audio></div>` : nothing}` : html`${m.text ? this._renderMarkdown(m.id, m.text) : nothing}${m.audio ? html`<div class="assistant-audio-player"><div class="audio-header"><span class="audio-badge">🎵 Voice Reply</span><button class="replay-btn" @click=${() => this._replayAudio(m.audio!)} title="Replay voice audio">🔄 Replay</button></div><audio controls preload="metadata" src="${m.audio.url || `data:${m.audio.mimeType};base64,${m.audio.data}`}"></audio></div>` : nothing}`}${m.isApprovalPrompt && this._awaitingApproval ? html`<div class="approval-card"><div class="approval-body"><div class="approval-title"><span>⏸</span> Action Requires Approval</div>${m.approvalData ? html`<div class="approval-tool">Tool: <code>${m.approvalData.tool}</code></div><div class="approval-params-header">Parameters:</div><pre class="approval-params"><code>${typeof m.approvalData.params === 'string' ? m.approvalData.params : JSON.stringify(m.approvalData.params, null, 2)}</code></pre>` : html`<p style="margin: 4px 0 0 0; font-size: 12px; color: var(--text-muted);">This session has paused to wait for your permission.</p>`}</div><div class="approval-actions">${this._showDenyReason ? html`<textarea class="deny-reason" .value=${this._denyReason} @input=${(e: any) => this._denyReason = e.target.value} placeholder="Reason for denial (optional)..."></textarea>` : nothing}<button class="btn-approve" @click=${() => this._sendApprove(m)}>Approve</button><button class="btn-deny" @click=${() => this._sendDeny(m)}>${this._showDenyReason ? 'Send Feedback' : 'Deny'}</button></div></div>` : nothing}</div>
               <div class="timestamp timestamp-${m.role}">${this._formatTime(m.timestamp)}</div>
            </div>
          `)}
        </div>
        
        <div class="input-wrapper">
          ${this._attachmentError ? html`<div class="attachment-error">${this._attachmentError}</div>` : nothing}
          ${this._audioAttachment ? html`<div class="attachment-row"><span>🎵 ${this._audioAttachment.name} (${Math.round(this._audioAttachment.size / 1024)} KB)</span><button aria-label="Remove attachment" @click=${this._clearAttachment}>×</button></div>` : nothing}
          <div class="input-area">
            <input class="attachment-input" type="file" accept=".mp3,.wav,.ogg,.webm,audio/mpeg,audio/wav,audio/ogg,audio/webm,video/webm" @change=${this._onAudioFileSelected} />
            <button class="attach-btn" aria-label="Attach audio file" title="Attach audio (MP3, WAV, OGG, WebM)" @click=${this._triggerFilePicker}>📎</button>
            <button class="mic-btn ${this._isRecording ? 'recording' : ''}" aria-label="${this._isRecording ? 'Stop recording' : 'Record audio'}" title="${this._isRecording ? 'Stop recording' : 'Record audio'}" @click=${this._toggleRecording}>${this._isRecording ? '⏹' : '🎤'}</button>
            <button class="tts-btn ${this._autoPlaySpeech ? 'active' : ''}" aria-label="${this._autoPlaySpeech ? 'Disable voice reply auto-play' : 'Enable voice reply auto-play'}" title="${this._autoPlaySpeech ? 'Voice auto-play: On' : 'Voice auto-play: Off'}" @click=${() => { this._autoPlaySpeech = !this._autoPlaySpeech; }}>${this._autoPlaySpeech ? '🔊' : '🔈'}</button>
            <textarea
              aria-label="Message Amsha Agent"
              placeholder="Ask Amsha Agent anything…"
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
            <button aria-label="Send message" class="send-btn" ?disabled="${(!this._input.trim() && !this._audioAttachment) || this._wsStatus !== 'open' || this._awaitingApproval || this._encodingAttachment || this._isRecording}" @click="${this._send}">
               <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><line x1="22" y1="2" x2="11" y2="13"></line><polygon points="22 2 15 22 11 13 2 9 22 2"></polygon></svg>
            </button>
          </div>
        </div>
      </div>
    `;
  }
}
