import { AgentEvent } from './generated/models';
import { EventDeduplicator } from './event-dedup';
import { AgentTraceEvent, traceEventId } from '../types/trace';

/**
 * Anything the SSE channel can deliver: the pre-existing agent events plus the versioned
 * execution trace added by the cockpit feature.
 */
export type SsePayload = AgentEvent | AgentTraceEvent;

export class SseManager {
  private _reader: ReadableStreamDefaultReader<string> | null = null;
  // Uses the global timer rather than `window.setTimeout` so the manager also works outside a
  // browser (unit tests, SSR bootstrap). Both resolve to the same function in a browser.
  private _retryTimeout: ReturnType<typeof setTimeout> | null = null;
  private _retryCount = 0;
  private _controller: AbortController | null = null;
  private readonly _dedup = new EventDeduplicator();

  constructor(
    private readonly url: string,
    private readonly apiKey: string,
    private readonly onEvent: (event: SsePayload) => void,
    private readonly onStatusChange: (status: 'connected' | 'disconnected' | 'error') => void
  ) {}

  async connect(): Promise<void> {
    this._cleanup();
    this._controller = new AbortController();
    
    try {
      const headers: Record<string, string> = {
        'Accept': 'text/event-stream',
      };
      if (this.apiKey && this.apiKey !== 'authenticated') {
        headers['X-AMSHA-API-KEY'] = this.apiKey;
      }
      const response = await fetch(this.url, {
        credentials: 'include',
        headers,
        signal: this._controller.signal
      });

      if (!response.ok || !response.body) {
        this._scheduleReconnect('error');
        return;
      }

      this.onStatusChange('connected');
      this._retryCount = 0;
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
            const event: SsePayload = JSON.parse(raw);
            // Reconnect replay is safe: the server re-sends pending events by eventId, and a
            // duplicate must never pulse a station or double-count a run statistic.
            if (this._dedup.accept(traceEventId(event))) {
              this.onEvent(event);
            }
          } catch { /* parse error - ignore */ }
        }
      }
    }
  }

  private _scheduleReconnect(status: 'disconnected' | 'error'): void {
    if (this._retryTimeout !== null) return;
    this.onStatusChange(status);
    this._cleanup();
    
    // Exponential backoff: initial 1s * 2^retryCount, capped at 30s
    // Jitter: +/- 20%
    const baseDelay = Math.min(1000 * Math.pow(2, this._retryCount), 30000);
    const jitter = baseDelay * 0.2 * (Math.random() * 2 - 1);
    const delay = Math.max(1000, baseDelay + jitter);

    console.debug(`[SseManager] ${status}. Reconnecting in ${Math.round(delay)}ms (attempt ${this._retryCount + 1})`);
    
    this._retryCount++;
    this._retryTimeout = setTimeout(() => {
      this._retryTimeout = null;
      this.connect();
    }, delay);
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

  /** Resets the event de-duplicator; call when the run identity changes. */
  resetDedup(): void {
    this._dedup.reset();
  }
}
