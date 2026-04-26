import { AgentEvent } from './types';

export class SseManager {
  private _reader: ReadableStreamDefaultReader<string> | null = null;
  private _retryTimeout: number | null = null;
  private _controller: AbortController | null = null;

  constructor(
    private readonly url: string,
    private readonly apiKey: string,
    private readonly onEvent: (event: AgentEvent) => void,
    private readonly onStatusChange: (status: 'connected' | 'disconnected' | 'error') => void
  ) {}

  async connect(): Promise<void> {
    this._cleanup();
    this._controller = new AbortController();
    
    try {
      const response = await fetch(this.url, {
        headers: { 
          'Accept': 'text/event-stream',
          'X-ARCCLAW-API-KEY': this.apiKey
        },
        signal: this._controller.signal
      });

      if (!response.ok || !response.body) {
        this._scheduleReconnect('error');
        return;
      }

      this.onStatusChange('connected');
      this._reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
      this._readLoop().catch(() => this._scheduleReconnect('error'));
    } catch (err: any) {
      if (err.name !== 'AbortError') {
        this._scheduleReconnect('error');
      }
    }
  }

  private async _readLoop(): Promise<void> {
    let buffer = '';
    while (this._reader) {
      const { done, value } = await this._reader.read();
      if (done) { 
        this._scheduleReconnect('disconnected'); 
        break; 
      }
      
      buffer += value;
      const lines = buffer.split('\n');
      buffer = lines.pop() ?? '';

      for (const line of lines) {
        const cleaned = line.trim();
        if (cleaned.startsWith('data:')) {
          try {
            const raw = cleaned.slice(5).trim();
            if (!raw || raw === ': heartbeat') continue;
            const event: AgentEvent = JSON.parse(raw);
            this.onEvent(event);
          } catch { /* parse error - ignore */ }
        }
      }
    }
  }

  private _scheduleReconnect(status: 'disconnected' | 'error'): void {
    if (this._retryTimeout !== null) return;
    this.onStatusChange(status);
    this._cleanup();
    
    this._retryTimeout = window.setTimeout(() => {
      this._retryTimeout = null;
      this.connect();
    }, 5000);
  }

  private _cleanup(): void {
    if (this._retryTimeout !== null) {
      clearTimeout(this._retryTimeout);
      this._retryTimeout = null;
    }
    if (this._reader) {
      this._reader.cancel().catch(() => {});
      this._reader = null;
    }
    if (this._controller) {
      this._controller.abort();
      this._controller = null;
    }
  }

  disconnect(): void {
    this._cleanup();
    this.onStatusChange('disconnected');
  }
}
