import { AgentEvent } from './types';

export class SseManager {
  private _reader: ReadableStreamDefaultReader<string> | null = null;

  constructor(
    private readonly url: string,
    private readonly apiKey: string,
    private readonly onEvent: (event: AgentEvent) => void,
    private readonly onStatusChange: (status: 'connected' | 'disconnected' | 'error') => void
  ) {}

  async connect(): Promise<void> {
    try {
      const response = await fetch(`${this.url}?key=${encodeURIComponent(this.apiKey)}`, {
        headers: {
          'Accept': 'text/event-stream'
        },
      });
      if (!response.ok || !response.body) {
        this.onStatusChange('error');
        return;
      }
      this.onStatusChange('connected');
      this._reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
      this._readLoop();
    } catch {
      this.onStatusChange('error');
    }
  }

  private async _readLoop(): Promise<void> {
    if (!this._reader) return;
    let buffer = '';
    while (true) {
      try {
        const { done, value } = await this._reader.read();
        if (done) { 
          this.onStatusChange('disconnected'); 
          break; 
        }
        buffer += value;
        const lines = buffer.split('\n');
        buffer = lines.pop() ?? '';
        for (const line of lines) {
          if (line.startsWith('data:')) {
            try {
              const event: AgentEvent = JSON.parse(line.slice(5).trim());
              this.onEvent(event);
            } catch { /* malformed frame — ignore */ }
          }
        }
      } catch {
        this.onStatusChange('error');
        break;
      }
    }
  }

  disconnect(): void {
    if (this._reader) {
      this._reader.cancel().catch(() => {});
      this._reader = null;
    }
    this.onStatusChange('disconnected');
  }
}
