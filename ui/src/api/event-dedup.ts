/**
 * Bounded `eventId` de-duplication for the SSE transport.
 *
 * The server replays pending events when a client (re)connects, and a browser reconnect can
 * overlap with the in-flight stream. Without de-duplication the UI would pulse the same station
 * twice and double-count run statistics.
 *
 * Only events that carry an `eventId` are tracked; legacy `HitlEvent`, `AgentStatusEvent` and
 * `HeartbeatEvent` payloads have none and must always pass through.
 */

/** Default number of retained ids, enough for a long turn without unbounded growth. */
export const DEFAULT_MAX_TRACKED_EVENT_IDS = 2000;

export class EventDeduplicator {
  private readonly _seen = new Set<string>();
  private readonly _order: string[] = [];

  constructor(private readonly maxTracked: number = DEFAULT_MAX_TRACKED_EVENT_IDS) {}

  /**
   * Returns `true` when the payload should be dispatched, i.e. its `eventId` has not been seen
   * before. Returns `true` unconditionally when there is no `eventId`, so legacy events are
   * never dropped.
   */
  accept(eventId: string | undefined | null): boolean {
    if (!eventId) return true;
    if (this._seen.has(eventId)) return false;

    this._seen.add(eventId);
    this._order.push(eventId);
    while (this._order.length > this.maxTracked) {
      const evicted = this._order.shift();
      if (evicted !== undefined) this._seen.delete(evicted);
    }
    return true;
  }

  /** True when the given id was already dispatched. */
  has(eventId: string): boolean {
    return this._seen.has(eventId);
  }

  /** Number of ids currently tracked. */
  get size(): number {
    return this._seen.size;
  }

  /** Drops all tracked ids; used when the run identity changes. */
  reset(): void {
    this._seen.clear();
    this._order.length = 0;
  }
}
