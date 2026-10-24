import { WsInboundFrame, WsOutboundMessage } from './types';

export class WsManager {
  private _ws: WebSocket | null = null;

  constructor(
    private readonly url: string,
    private readonly onFrame: (frame: WsInboundFrame) => void,
    private readonly onStatusChange: (status: 'connecting' | 'transport-open' | 'closed' | 'error') => void
  ) {}

  connect(): void {
    this._cleanup();
    this._ws = new WebSocket(this.url);
    this.onStatusChange('connecting');

    this._ws.onopen = () => this.onStatusChange('transport-open');
    
    this._ws.onclose = () => {
      this.onStatusChange('closed');
    };
    
    this._ws.onerror = () => {
      this.onStatusChange('error');
    };
    
    this._ws.onmessage = (e: MessageEvent) => {
      try {
        const frame: WsInboundFrame = JSON.parse(e.data);
        this.onFrame(frame);
      } catch { /* ignore malformed */ }
    };
  }

  private _cleanup(): void {
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
    this._cleanup();
    this.onStatusChange('closed');
  }
}
