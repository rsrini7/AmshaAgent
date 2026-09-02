import { describe, expect, it } from 'vitest';

import { EventDeduplicator } from './event-dedup';

describe('EventDeduplicator', () => {
  it('dispatches an id the first time and drops the replay', () => {
    const dedup = new EventDeduplicator();

    expect(dedup.accept('event-1')).toBe(true);
    expect(dedup.accept('event-1')).toBe(false);
  });

  it('never drops legacy events that carry no eventId', () => {
    const dedup = new EventDeduplicator();

    expect(dedup.accept(undefined)).toBe(true);
    expect(dedup.accept(undefined)).toBe(true);
    expect(dedup.accept(null)).toBe(true);
    expect(dedup.accept('')).toBe(true);
    expect(dedup.size).toBe(0);
  });

  it('keeps distinct ids independent', () => {
    const dedup = new EventDeduplicator();

    expect(dedup.accept('a')).toBe(true);
    expect(dedup.accept('b')).toBe(true);
    expect(dedup.accept('a')).toBe(false);
    expect(dedup.accept('b')).toBe(false);
    expect(dedup.size).toBe(2);
  });

  it('bounds memory by evicting the oldest ids', () => {
    const dedup = new EventDeduplicator(3);

    dedup.accept('a');
    dedup.accept('b');
    dedup.accept('c');
    dedup.accept('d');

    expect(dedup.size).toBe(3);
    expect(dedup.has('a')).toBe(false);
    expect(dedup.has('d')).toBe(true);
    // 'a' was evicted, so it looks new again rather than growing without bound.
    expect(dedup.accept('a')).toBe(true);
  });

  it('reset clears every tracked id', () => {
    const dedup = new EventDeduplicator();
    dedup.accept('a');
    dedup.reset();

    expect(dedup.size).toBe(0);
    expect(dedup.accept('a')).toBe(true);
  });
});
