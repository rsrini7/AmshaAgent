import { WsInboundFrame, WsOutboundMessage } from './types';

export class WsManager {
  private _ws: WebSocket | null = null;
  private _retryCount = 0;
  private _retryTimeout: number | null = null;
  private _intentionalClose = false;

  constructor(
    private readonly url: string,
    private readonly onFrame: (frame: WsInboundFrame) => void,
    private readonly onStatusChange: (status: 'connecting' | 'open' | 'closed' | 'error') => void
  ) {}

  connect(): void {
    this._intentionalClose = false;
    this._cleanup();
    this._ws = new WebSocket(this.url);
    this.onStatusChange('connecting');

    this._ws.onopen = () => {
      this._retryCount = 0;
      this.onStatusChange('open');
    };
    
    this._ws.onclose = () => {
      this.onStatusChange('closed');
      this._scheduleReconnect();
    };
    
    this._ws.onerror = () => {
      this.onStatusChange('error');
      // onclose will follow and trigger reconnect
    };
    
    this._ws.onmessage = (e: MessageEvent) => {
      try {
        const frame: WsInboundFrame = JSON.parse(e.data);
        this.onFrame(frame);
      } catch { /* ignore malformed */ }
    };
  }

  private _scheduleReconnect(): void {
    if (this._intentionalClose || this._retryTimeout !== null) return;

    const baseDelay = Math.min(1000 * Math.pow(2, this._retryCount), 30000);
    const jitter = baseDelay * 0.2 * (Math.random() * 2 - 1);
    const delay = Math.max(1000, baseDelay + jitter);

    console.warn(`[WsManager] Connection closed. Reconnecting in ${Math.round(delay)}ms (attempt ${this._retryCount + 1})`);

    this._retryCount++;
    this._retryTimeout = window.setTimeout(() => {
      this._retryTimeout = null;
      this.connect();
    }, delay);
  }

  private _cleanup(): void {
    if (this._retryTimeout !== null) {
      clearTimeout(this._retryTimeout);
      this._retryTimeout = null;
    }
    if (this._ws) {
      this._ws.onopen = null;
      this._ws.onclose = null;
      this._ws.onerror = null;
      this._ws.onmessage = null;
      this._ws.close();
      this._ws = null;
    }
  }

  send(msg: WsOutboundMessage): void {
    if (this._ws?.readyState === WebSocket.OPEN) {
      this._ws.send(JSON.stringify(msg));
    }
  }

  disconnect(): void {
    this._intentionalClose = true;
    this._cleanup();
    this.onStatusChange('closed');
  }
}
