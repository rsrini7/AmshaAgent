import { describe, expect, it, vi, afterEach } from 'vitest';
import { ArcApiError, ArcClient } from '../api/client';
import type { PendingApprovalDto } from '../api/types';
import { ArcHitlPanel } from './arc-hitl-panel';

const approval: PendingApprovalDto = {
  taskId: 'task-1',
  sessionId: 'session-1',
  toolName: 'search_web',
  params: { query: 'latest release' },
  requestedAt: '2026-10-24T12:00:00.000Z',
  agentName: 'Researcher',
};

function panel(): ArcHitlPanel {
  const component = new ArcHitlPanel();
  (component as any)._appContext = { apiKey: 'test-api-key' };
  component.approvals = [approval];
  return component;
}

describe('ArcHitlPanel action and reconciliation behavior', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('dispatches refresh and approval events and keeps idempotency keys stable per action', async () => {
    const component = panel();
    const refreshEvents: Event[] = [];
    const actionEvents: CustomEvent[] = [];
    component.addEventListener('request-approvals-refresh', (event) => refreshEvents.push(event));
    component.addEventListener('approval-action', (event) => actionEvents.push(event as CustomEvent));
    (component as any)._manualRefresh();
    expect(refreshEvents).toHaveLength(1);

    const firstKey = (component as any)._idempotencyKey('task-1', 'approved', 'search_web', approval.requestedAt);
    const repeatedKey = (component as any)._idempotencyKey('task-1', 'approved', 'search_web', approval.requestedAt);
    const rejectKey = (component as any)._idempotencyKey('task-1', 'rejected', 'search_web', approval.requestedAt);
    expect(repeatedKey).toBe(firstKey);
    expect(rejectKey).not.toBe(firstKey);

    component.channelCommandMode = true;
    await (component as any)._approve('task-1');
    expect(actionEvents[0]?.detail).toEqual({ taskId: 'task-1', action: 'approved' });
    expect((component as any)._processing.has('task-1')).toBe(false);

    (component as any)._toggleReject('task-1');
    expect((component as any)._rejecting.has('task-1')).toBe(true);
    await (component as any)._submitReject('task-1');
    expect(actionEvents[1]?.detail).toEqual({ taskId: 'task-1', action: 'rejected' });
    expect((component as any)._rejecting.has('task-1')).toBe(false);
  });

  it('approves with an idempotency key and suppresses duplicate in-flight actions', async () => {
    const component = panel();
    const approveHitl = vi.spyOn(ArcClient.prototype, 'approveHitl').mockResolvedValue({
      status: 'approved',
      taskId: approval.taskId,
    });
    const events: CustomEvent[] = [];
    component.addEventListener('approval-action', (event) => events.push(event as CustomEvent));

    await (component as any)._approve(approval);
    expect(approveHitl).toHaveBeenCalledWith(approval.taskId, 'Approved by user', expect.objectContaining({
      idempotencyKey: expect.stringContaining('arc-hitl-approved-task-1'),
      toolName: approval.toolName,
    }));
    expect(events[0]?.detail).toEqual({ taskId: approval.taskId, action: 'approved' });
    expect((component as any)._error[approval.taskId]).toBe('');

    (component as any)._processing.add(approval.taskId);
    await (component as any)._approve(approval);
    expect(approveHitl).toHaveBeenCalledOnce();
  });

  it('marks an uncertain approval result and reconciles pending state before retry', async () => {
    const component = panel();
    vi.spyOn(ArcClient.prototype, 'approveHitl').mockRejectedValue(new ArcApiError(503, 'gateway timeout'));
    vi.spyOn(ArcClient.prototype, 'getHitl').mockResolvedValue({
      items: [approval],
      page: 0,
      size: 50,
      totalCount: 1,
      totalPages: 1,
    });

    await (component as any)._approve(approval);
    expect((component as any)._statusUnknown.has(approval.taskId)).toBe(true);
    expect((component as any)._error[approval.taskId]).toContain('still pending');
    expect((component as any)._isUncertainFailure(new ArcApiError(429, 'limited'))).toBe(true);
    expect((component as any)._isUncertainFailure(new ArcApiError(400, 'invalid'))).toBe(false);
    expect((component as any)._isUncertainFailure(new Error('network timeout'))).toBe(true);
    expect((component as any)._isAlreadyResolved(new ArcApiError(409, 'resolved'))).toBe(true);
    expect((component as any)._isAlreadyResolved(new ArcApiError(500, 'server error'))).toBe(false);
    expect((component as any)._errorMessage(new Error('specific'), 'fallback')).toBe('specific');
    expect((component as any)._errorMessage('unknown', 'fallback')).toBe('fallback');
  });

  it('recognizes matching approval records and reconciles already-resolved conflicts', async () => {
    const component = panel();
    const linkedApproval = { ...approval, taskId: 'different-task', sessionId: approval.taskId, toolName: 'parallel|search_web' };
    expect((component as any)._approvalKey(approval)).toBe('task-1:search_web:2026-10-24T12:00:00.000Z');
    expect((component as any)._sameApproval(linkedApproval, approval)).toBe(true);
    expect((component as any)._sameApproval({ ...approval, sessionId: 'other-session' }, linkedApproval)).toBe(false);
    expect((component as any)._sameApproval({ ...linkedApproval, toolName: 'parallel|other' }, approval)).toBe(false);

    const getHitl = vi.spyOn(ArcClient.prototype, 'getHitl').mockResolvedValue({
      items: [],
      page: 0,
      size: 50,
      totalCount: 0,
      totalPages: 1,
    });
    const resolvedEvents: CustomEvent[] = [];
    component.addEventListener('approval-action', (event) => resolvedEvents.push(event as CustomEvent));
    const client = new ArcClient('/api/v1', 'test-api-key');
    const resolved = await (component as any)._reconcileResolvedConflict(client, approval);
    expect(resolved).toBe(true);
    expect(resolvedEvents[0]?.detail).toEqual({ taskId: approval.taskId, action: 'resolved' });
    expect((component as any)._resolvedElsewhere.has((component as any)._approvalKey(approval))).toBe(true);

    getHitl.mockResolvedValue({ items: [approval], page: 0, size: 50, totalCount: 1, totalPages: 1 });
    const stillPending = await (component as any)._reconcileResolvedConflict(client, approval);
    expect(stillPending).toBe(false);
    expect((component as any)._statusConflict.has(approval.taskId)).toBe(true);
    expect((component as any)._statusUnknown.has(approval.taskId)).toBe(true);

    getHitl.mockRejectedValue(new Error('offline'));
    await (component as any)._reconcileResolvedConflict(client, approval);
    expect((component as any)._statusNotice[approval.taskId]).toContain('Could not confirm');
  });

  it('checks status, retries the last action, and formats relative time and parameters', async () => {
    const component = panel();
    const getHitl = vi.spyOn(ArcClient.prototype, 'getHitl').mockResolvedValue({
      items: [],
      page: 0,
      size: 50,
      totalCount: 0,
      totalPages: 1,
    });
    await (component as any)._checkStatus(approval.taskId);
    expect(getHitl).toHaveBeenCalledOnce();

    (component as any)._lastActions.set(approval.taskId, 'rejected');
    const rejectHitl = vi.spyOn(ArcClient.prototype, 'rejectHitl').mockResolvedValue({ status: 'rejected', taskId: approval.taskId });
    await (component as any)._retry(approval.taskId);
    expect(rejectHitl).toHaveBeenCalledWith(
      approval.taskId,
      '',
      expect.objectContaining({ idempotencyKey: expect.any(String), toolName: approval.toolName }),
    );

    expect((component as any)._formatTime(new Date().toISOString())).toBe('now');
    expect((component as any)._formatTime(new Date(Date.now() - 120_000).toISOString())).toContain('minute');
    expect((component as any)._formatParams(null)).toBe('No parameters');
    expect((component as any)._formatParams('[]')).toBe('[]');
    expect((component as any)._formatParams('{"a":1}')).toContain('a: 1');
  });
});
