import { LitElement } from 'lit';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ArcClient } from '../api/client';
import { ArcAuditPanel } from './arc-audit-panel';

function flattenLit(value: unknown): string {
  if (typeof value === 'string' || typeof value === 'number') return String(value);
  if (Array.isArray(value)) return value.map(flattenLit).join('');
  if (value && typeof value === 'object' && 'strings' in value && 'values' in value) {
    const template = value as { strings: readonly string[]; values: unknown[] };
    return template.strings.reduce(
      (result, part, index) => result + part + (index < template.values.length ? flattenLit(template.values[index]) : ''),
      '',
    );
  }
  return '';
}

const auditEvent = {
  id: 'event-1',
  sessionId: 'session-123456789012345',
  userId: 'user-1',
  agentRole: 'Researcher',
  goalHash: 'goal-hash',
  outcome: 'FINISH',
  eventType: 'TOOL_EXECUTED',
  eventDetail: '{"tool":"web_fetch"}',
  inputTokens: 120,
  outputTokens: 30,
  usdCost: 0.0123,
  iterationsUsed: 2,
  timestamp: '2026-10-24T12:00:00.000Z',
  hmacHash: 'chain-hash',
};

describe('ArcAuditPanel', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('loads event types and audit rows, then renders event details and payload', async () => {
    const storage = { getItem: vi.fn(() => null), setItem: vi.fn() };
    vi.stubGlobal('sessionStorage', storage);
    vi.spyOn(ArcClient.prototype, 'getAuditEventTypes').mockResolvedValue({ eventTypes: ['TOOL_EXECUTED'] });
    vi.spyOn(ArcClient.prototype, 'getAuditEvents').mockResolvedValue({ events: [auditEvent], total: 1 } as any);
    const component = new ArcAuditPanel();
    (component as any)._appContext = { apiKey: 'test-api-key' };

    await (component as any).loadEventTypes();
    await (component as any).loadEvents();

    expect(storage.setItem).toHaveBeenCalledWith('amsha_audit_event_types', '["TOOL_EXECUTED"]');
    expect((component as any).loading).toBe(false);
    const rendered = flattenLit(component.render());
    expect(rendered).toContain('Audit Log');
    expect(rendered).toContain('TOOL_EXECUTED');
    expect(rendered).toContain('FINISH');
    expect(rendered).toContain('session-123456789012');
    expect(rendered).toContain('$0.0123');

    (component as any).selectedEvent = auditEvent;
    const drawer = flattenLit((component as any).renderDrawer());
    expect(drawer).toContain('Audit Event Details');
    expect(drawer).toContain('Structured Payload');
    expect(drawer).toContain('web_fetch');
    expect(drawer).toContain('chain-hash');

    (component as any)._onKeyDown({ key: 'Escape' } as KeyboardEvent);
    expect((component as any).selectedEvent).toBeNull();
  });

  it('uses cached event types and falls back to the built-in list when cache and API fail', async () => {
    const cachedStorage = { getItem: vi.fn(() => '["CACHED_EVENT"]'), setItem: vi.fn() };
    vi.stubGlobal('sessionStorage', cachedStorage);
    const getTypes = vi.spyOn(ArcClient.prototype, 'getAuditEventTypes').mockRejectedValue(new Error('offline'));
    const component = new ArcAuditPanel();

    await (component as any).loadEventTypes();
    expect((component as any).eventTypes).toEqual(['CACHED_EVENT']);
    expect(getTypes).not.toHaveBeenCalled();

    cachedStorage.getItem.mockReturnValue('{bad json');
    await (component as any).loadEventTypes();
    expect(getTypes).toHaveBeenCalledOnce();
    expect((component as any).eventTypes).toContain('TOOL_EXECUTED');
  });

  it('handles failed audit queries and formats optional values safely', async () => {
    vi.spyOn(ArcClient.prototype, 'getAuditEvents').mockRejectedValue(new Error('offline'));
    const component = new ArcAuditPanel();

    await (component as any).loadEvents();
    expect((component as any).loading).toBe(false);
    expect((component as any).events).toEqual([]);
    expect((component as any).formatDate(null)).toBe('—');
    expect((component as any).formatDate(auditEvent.timestamp)).toBe(new Date(auditEvent.timestamp).toLocaleString());
    expect((component as any).formatCost(0)).toBe('—');
    expect((component as any).formatCost(1.2)).toBe('$1.2000');
    expect(flattenLit((component as any).renderPayloadJson(null))).toBe('');
    expect(flattenLit((component as any).renderPayloadJson('not json'))).toBe('');
    expect(flattenLit((component as any).renderPayloadJson('{"safe":true}'))).toContain('safe');
  });

  it('categorizes audit event types and handles escape only when the drawer is open', () => {
    const component = new ArcAuditPanel();
    const cases: Array<[string, string]> = [
      ['TOOL_INVOKED', 'TOOL'],
      ['CRON_FIRED', 'CRON'],
      ['LOOP_TIMEOUT', 'ERROR'],
      ['MEMORY_INGESTED', 'MEMORY'],
      ['SESSION_CREATED', 'SESSION'],
      ['ONBOARDING_COMPLETED', 'SETTINGS'],
      ['AUTH_REJECTED', 'AUTH'],
      ['CHECKPOINT_CREATED', 'CHECKPOINT'],
      ['DELEGATION_DISPATCHED', 'DELEGATION'],
      ['MCP_SERVER_CONNECTED', 'MCP'],
      ['APPROVAL_GRANTED', 'APPROVAL'],
      ['HMAC_INVALID', 'SECURITY'],
      ['SKILL_LOADED', 'CRON'],
      ['PII_SCAN_REDACTED', 'SECURITY'],
      ['OTHER_EVENT', 'OTHER_EVENT'],
    ];
    for (const [eventType, category] of cases) {
      const rendered = flattenLit((component as any).renderEventType(eventType));
      expect(rendered).toContain(`event-type-${category === eventType ? '' : category}`);
    }
    expect(flattenLit((component as any).renderOutcome('ERROR'))).toContain('outcome-ERROR');
    expect(flattenLit((component as any).renderOutcome('FINISH'))).toContain('outcome-FINISH');

    (component as any)._onKeyDown({ key: 'Escape' } as KeyboardEvent);
    expect((component as any).selectedEvent).toBeNull();
  });

  it('registers and removes the keyboard handler and reloads on an authenticated context change', async () => {
    vi.spyOn(LitElement.prototype, 'connectedCallback').mockImplementation(() => {});
    vi.spyOn(LitElement.prototype, 'disconnectedCallback').mockImplementation(() => {});
    const documentStub = { addEventListener: vi.fn(), removeEventListener: vi.fn() };
    vi.stubGlobal('document', documentStub);
    const component = new ArcAuditPanel();
    const loadTypes = vi.spyOn(component as any, 'loadEventTypes').mockResolvedValue(undefined);
    const loadEvents = vi.spyOn(component as any, 'loadEvents').mockResolvedValue(undefined);
    (component as any)._appContext = { apiKey: 'test-api-key' };

    await component.connectedCallback();
    expect(loadTypes).toHaveBeenCalledOnce();
    expect(loadEvents).toHaveBeenCalledOnce();
    expect(documentStub.addEventListener).toHaveBeenCalledWith('keydown', expect.any(Function));

    component.updated(new Map([['_appContext', undefined]]));
    expect(loadTypes).toHaveBeenCalledTimes(2);
    expect(loadEvents).toHaveBeenCalledTimes(2);

    component.disconnectedCallback();
    expect(documentStub.removeEventListener).toHaveBeenCalledWith('keydown', expect.any(Function));
  });
});
