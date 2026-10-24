import { LitElement } from 'lit';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ArcClient } from '../api/client';
import { ArcSchedulerPanel } from './arc-scheduler-panel';

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

const activeCron = {
  id: 'cron-1',
  sessionId: 'session-1',
  userId: 'user-1',
  chatId: 'chat-1',
  channelType: 'web',
  channelPlatformId: 'platform-1',
  cronExpression: '0 9 * * *',
  timezone: 'UTC',
  runOnce: false,
  goal: 'Review daily status',
  nextFireTime: '2026-10-25T09:00:00Z',
  lastFireTime: '2026-10-24T09:00:00Z',
  status: 'ACTIVE',
  executionRole: 'Researcher',
  errorReason: '',
  lastDeliveryId: 'delivery-1',
  createdAt: '2026-10-20T00:00:00Z',
  updatedAt: '2026-10-24T00:00:00Z',
};

const failedCron = {
  ...activeCron,
  id: 'cron-2',
  status: 'FAILED',
  runOnce: true,
  channelPlatformId: '',
  executionRole: '',
  errorReason: 'Provider unavailable',
};

describe('ArcSchedulerPanel', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('loads scheduled jobs and renders list, statuses, and job details', async () => {
    vi.spyOn(ArcClient.prototype, 'getCrons').mockResolvedValue({ crons: [activeCron, failedCron], total: 2, page: 0, size: 50 });
    const component = new ArcSchedulerPanel();
    (component as any)._appContext = { apiKey: 'test-api-key' };
    await (component as any).loadCrons();

    const list = flattenLit(component.render());
    expect(list).toContain('Review daily status');
    expect(list).toContain('ACTIVE');
    expect(list).toContain('FAILED');
    expect(list).toContain('Showing 2 of 2 jobs');
    expect((component as any).loading).toBe(false);
    expect(flattenLit((component as any).renderStatus('SKIPPED'))).toContain('status-SKIPPED');
    expect((component as any).formatDate(null)).toBe('—');

    (component as any).selectedCron = activeCron;
    const drawer = flattenLit((component as any).renderDrawer());
    expect(drawer).toContain('Scheduled Job Details');
    expect(drawer).toContain('0 9 * * *');
    expect(drawer).toContain('Researcher');

    (component as any).selectedCron = failedCron;
    const failedDrawer = flattenLit((component as any).renderDrawer());
    expect(failedDrawer).toContain('One-shot');
    expect(failedDrawer).toContain('Provider unavailable');
    expect(failedDrawer).toContain('Execution History');
    expect(failedDrawer).toContain('No executions yet.');
  });

  it('renders execution history and handles run-load failures', async () => {
    const component = new ArcSchedulerPanel();
    (component as any)._appContext = { apiKey: 'test-api-key' };
    (component as any).selectedCron = activeCron;
    const run = {
      id: 'run-1',
      cronId: 'cron-1',
      sessionId: 'session-1',
      status: 'COMPLETED',
      startedAt: '2026-10-24T09:00:00Z',
      finishedAt: '2026-10-24T09:00:02Z',
      errorReason: '',
      iterationsUsed: 1,
      inputTokens: 10,
      outputTokens: 5,
      usdCost: 0.001,
      createdAt: '2026-10-24T09:00:02Z',
    };
    vi.spyOn(ArcClient.prototype, 'getCronRuns').mockResolvedValue({ runs: [run], total: 1, page: 0, size: 20 });
    await (component as any).loadCronRuns('cron-1');
    const drawer = flattenLit((component as any).renderDrawer());
    expect(drawer).toContain('Tokens');
    expect(drawer).toContain('15');
    expect(drawer).toContain('$0.0010');
    expect((component as any).loadingRuns).toBe(false);

    vi.spyOn(ArcClient.prototype, 'getCronRuns').mockRejectedValue(new Error('offline'));
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    await (component as any).loadCronRuns('cron-1');
    expect((component as any).cronRuns).toEqual([]);
    expect(consoleError).toHaveBeenCalled();
  });

  it('routes job actions through the client and refreshes after confirmation', async () => {
    const component = new ArcSchedulerPanel();
    (component as any)._appContext = { apiKey: 'test-api-key' };
    const reload = vi.spyOn(component as any, 'loadCrons').mockResolvedValue(undefined);
    const deleteCron = vi.spyOn(ArcClient.prototype, 'deleteCron').mockResolvedValue({});
    const triggerCron = vi.spyOn(ArcClient.prototype, 'triggerCron').mockResolvedValue({});
    const pauseCron = vi.spyOn(ArcClient.prototype, 'pauseCron').mockResolvedValue({});
    const resumeCron = vi.spyOn(ArcClient.prototype, 'resumeCron').mockResolvedValue({});
    const confirmStub = vi.fn(() => false);
    vi.stubGlobal('confirm', confirmStub);

    await (component as any).deleteCron('cron-1');
    expect(deleteCron).not.toHaveBeenCalled();
    confirmStub.mockReturnValue(true);
    await (component as any).deleteCron('cron-1');
    await (component as any).triggerCron('cron-1');
    await (component as any).pauseCron('cron-1');
    await (component as any).resumeCron('cron-1');

    expect(deleteCron).toHaveBeenCalledOnce();
    expect(triggerCron).toHaveBeenCalledOnce();
    expect(pauseCron).toHaveBeenCalledOnce();
    expect(resumeCron).toHaveBeenCalledOnce();
    expect(reload).toHaveBeenCalledTimes(4);
  });

  it('selects a job and refreshes when the authenticated app context changes', async () => {
    vi.spyOn(LitElement.prototype, 'connectedCallback').mockImplementation(() => {});
    const component = new ArcSchedulerPanel();
    (component as any)._appContext = { apiKey: 'test-api-key' };
    const loadCrons = vi.spyOn(component as any, 'loadCrons').mockResolvedValue(undefined);
    const loadRuns = vi.spyOn(component as any, 'loadCronRuns').mockResolvedValue(undefined);

    await component.connectedCallback();
    component.updated(new Map([['_appContext', undefined]]));
    expect(loadCrons).toHaveBeenCalledTimes(2);

    (component as any).selectCron(activeCron);
    expect((component as any).selectedCron).toBe(activeCron);
    expect(loadRuns).toHaveBeenCalledWith('cron-1');

    (component as any).loading = true;
    component.updated(new Map([['_appContext', undefined]]));
    expect(loadCrons).toHaveBeenCalledTimes(2);
  });
});
