import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ResizeController } from '../composables/resize-controller';
import { ArcShell } from './arc-shell';
import { AgentTraceEvent, TRACE_EVENT_TYPE } from '../types/trace';

class MockStorage implements Storage {
  private store = new Map<string, string>();
  get length() {
    return this.store.size;
  }
  clear() {
    this.store.clear();
  }
  getItem(key: string) {
    return this.store.get(key) ?? null;
  }
  key(index: number) {
    return Array.from(this.store.keys())[index] ?? null;
  }
  removeItem(key: string) {
    this.store.delete(key);
  }
  setItem(key: string, val: string) {
    this.store.set(key, val);
  }
}

describe('Cockpit Layout & ResizeController', () => {
  beforeEach(() => {
    vi.stubGlobal('sessionStorage', new MockStorage());
    vi.stubGlobal('window', {
      location: { hash: '#/chat' },
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      setTimeout: vi.fn(),
      clearTimeout: vi.fn(),
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('provides required ARIA separator attributes', () => {
    const controller = new ResizeController({
      defaultWidth: 400,
      minWidth: 280,
      maxWidth: 800,
    });

    const attrs = controller.getAriaAttributes();
    expect(attrs.role).toBe('separator');
    expect(attrs.tabindex).toBe(0);
    expect(attrs['aria-orientation']).toBe('vertical');
    expect(attrs['aria-valuenow']).toBe(400);
    expect(attrs['aria-valuemin']).toBe(280);
    expect(attrs['aria-valuemax']).toBe(800);
    expect(attrs['aria-label']).toBe('Resize chat dock');
  });

  it('resizes via pointer drag and clamps to bounds', () => {
    let notifiedWidth = 0;
    const controller = new ResizeController({
      defaultWidth: 400,
      minWidth: 280,
      maxWidth: 600,
      onResize: (w) => {
        notifiedWidth = w;
      },
    });

    // Start drag at x=500
    controller.handlePointerDown({ clientX: 500 });
    expect(controller.isDragging).toBe(true);

    // Move pointer left to x=450 (delta = +50 -> dock width becomes 450)
    controller.handlePointerMove({ clientX: 450 });
    expect(controller.width).toBe(450);
    expect(notifiedWidth).toBe(450);
    expect(controller.getAriaAttributes()['aria-valuenow']).toBe(450);

    // Move pointer far right to x=800 (delta = -300 -> dock width clamped to min 280)
    controller.handlePointerMove({ clientX: 800 });
    expect(controller.width).toBe(280);

    // Move pointer far left to x=100 (delta = +400 -> dock width clamped to max 600)
    controller.handlePointerMove({ clientX: 100 });
    expect(controller.width).toBe(600);

    // Pointer up
    controller.handlePointerUp();
    expect(controller.isDragging).toBe(false);

    // Further move when not dragging has no effect
    controller.handlePointerMove({ clientX: 300 });
    expect(controller.width).toBe(600);
  });

  it('resizes via keyboard navigation (ArrowLeft, ArrowRight, Home, End)', () => {
    const controller = new ResizeController({
      defaultWidth: 400,
      minWidth: 300,
      maxWidth: 500,
      step: 20,
    });

    let prevented = false;
    const eventMock = (key: string) => ({
      key,
      preventDefault: () => {
        prevented = true;
      },
    });

    // ArrowLeft expands dock by step
    controller.handleKeyDown(eventMock('ArrowLeft'));
    expect(controller.width).toBe(420);
    expect(prevented).toBe(true);

    // ArrowRight shrinks dock by step
    controller.handleKeyDown(eventMock('ArrowRight'));
    expect(controller.width).toBe(400);

    // End sets to max width
    controller.handleKeyDown(eventMock('End'));
    expect(controller.width).toBe(500);

    // Home sets to min width
    controller.handleKeyDown(eventMock('Home'));
    expect(controller.width).toBe(300);
  });

  it('persists layout width in sessionStorage', () => {
    const controller1 = new ResizeController({
      storageKey: 'custom-dock-width',
      defaultWidth: 350,
    });
    controller1.setWidth(450);

    expect(sessionStorage.getItem('custom-dock-width')).toBe('450');

    // Creating new controller reloads persisted width
    const controller2 = new ResizeController({
      storageKey: 'custom-dock-width',
      defaultWidth: 350,
    });
    expect(controller2.width).toBe(450);
  });

  it('scopes ArcShell trace events exclusively to active run ID', () => {
    const shell = new ArcShell();
    (shell as any)._sessionId = 'sess-scope';

    const mockEv = (runId: string, stage: AgentTraceEvent['stage']): AgentTraceEvent => ({
      eventId: 'ev-' + Math.random(),
      runId,
      sessionId: 'sess-scope',
      turnNumber: 1,
      sequence: 0,
      stage,
      station: 'station_01_inbound',
      status: 'COMPLETED',
      riskLevel: null,
      detail: null,
      metrics: null,
      timestamp: '2026-09-01T12:00:00.000Z',
      eventType: TRACE_EVENT_TYPE,
      version: 1,
    });

    // Turn 1 events
    (shell as any)._handleSseEvent(mockEv('sess-scope:1', 'turn_start'));
    (shell as any)._handleSseEvent(mockEv('sess-scope:1', 'tool_error'));

    expect((shell as any)._currentRunId).toBe('sess-scope:1');
    expect((shell as any)._activeRunEvents).toHaveLength(2);

    // Turn 2 transition
    (shell as any)._handleSseEvent(mockEv('sess-scope:2', 'turn_start'));

    expect((shell as any)._currentRunId).toBe('sess-scope:2');
    const activeEvents = (shell as any)._activeRunEvents;
    expect(activeEvents).toHaveLength(1);
    expect(activeEvents[0].runId).toBe('sess-scope:2');
    expect(activeEvents.some((e: AgentTraceEvent) => e.stage === 'tool_error')).toBe(false);
  });
});
