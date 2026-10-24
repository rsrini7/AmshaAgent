import { afterEach, describe, expect, it, vi } from 'vitest';
import { WsManager } from './websocket';
import type { WsInboundFrame, WsOutboundMessage } from './types';

class MockWebSocket {
  static readonly OPEN = 1;
  static instances: MockWebSocket[] = [];

  readyState = 0;
  onopen: ((event: Event) => void) | null = null;
  onclose: ((event: CloseEvent) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  onmessage: ((event: MessageEvent) => void) | null = null;
  send = vi.fn();
  close = vi.fn();

  constructor(readonly url: string) {
    MockWebSocket.instances.push(this);
  }
}

describe('WsManager', () => {
  afterEach(() => {
    MockWebSocket.instances = [];
    vi.unstubAllGlobals();
  });

  it('connects, handles transport events, sends only while open, and cleans up', () => {
    vi.stubGlobal('WebSocket', MockWebSocket);
    const receivedFrames: WsInboundFrame[] = [];
    const statuses: string[] = [];
    const manager = new WsManager(
      'ws://amsha.test/api/v1/chat/stream',
      (frame) => receivedFrames.push(frame),
      (status) => statuses.push(status),
    );

    manager.connect();
    const firstSocket = MockWebSocket.instances[0];
    expect(firstSocket.url).toBe('ws://amsha.test/api/v1/chat/stream');
    expect(statuses).toEqual(['connecting']);

    firstSocket.onopen?.({} as Event);
    firstSocket.onerror?.({} as Event);
    firstSocket.onclose?.({} as CloseEvent);
    expect(statuses).toEqual(['connecting', 'transport-open', 'error', 'closed']);

    const frame: WsInboundFrame = { type: 'error', text: 'request rejected' };
    firstSocket.onmessage?.({ data: JSON.stringify(frame) } as MessageEvent);
    firstSocket.onmessage?.({ data: '{malformed' } as MessageEvent);
    expect(receivedFrames).toEqual([frame]);

    firstSocket.readyState = MockWebSocket.OPEN;
    const message: WsOutboundMessage = { type: 'AUTH', token: 'test-token' };
    manager.send(message);
    expect(firstSocket.send).toHaveBeenCalledWith(JSON.stringify(message));

    firstSocket.readyState = 0;
    manager.send({ type: 'message', text: 'ignored while closed' });
    expect(firstSocket.send).toHaveBeenCalledTimes(1);

    manager.connect();
    expect(firstSocket.close).toHaveBeenCalledOnce();
    expect(firstSocket.onmessage).toBeNull();
    expect(statuses.at(-1)).toBe('connecting');

    const replacementSocket = MockWebSocket.instances[1];
    manager.disconnect();
    expect(replacementSocket.close).toHaveBeenCalledOnce();
    expect(statuses.at(-1)).toBe('closed');
  });
});
