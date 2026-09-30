import { describe, expect, it } from 'vitest';

import {
  ArcClassifierPanel,
  CLASSIFIER_SCOPE_NOTICE,
  ClassifierView,
  EXPECTED_CLASSIFIER_IDS,
  describeClassifierSlot,
  resolveClassifierFailure,
  resolveClassifierStatus,
} from './arc-classifier-panel';
import { ArcApiError, ClassifierRuntimeResponse, ClassifierSnapshot } from '../api/client';

function slot(overrides: Partial<ClassifierSnapshot> = {}): ClassifierSnapshot {
  return {
    classifier: 'intent',
    startupEnabled: false,
    requestedEnabled: false,
    implementationAvailable: false,
    effectiveEnabled: false,
    acceptingRequests: false,
    draining: false,
    inFlight: 0,
    revision: 1,
    ...overrides,
  };
}

/** The shape the backend guarantees on a 200: exactly the three known slots, in enum order. */
function threeSlots(overrides: Partial<ClassifierSnapshot> = {}): ClassifierRuntimeResponse {
  return {
    classifiers: EXPECTED_CLASSIFIER_IDS.map((id) => slot({ classifier: id, ...overrides })),
  };
}

function renderPanel(view: ClassifierView): string {
  const panel = new ArcClassifierPanel();
  (panel as unknown as { _view: ClassifierView })._view = view;
  return JSON.stringify(panel.render());
}

describe('classifier slot derivation', () => {
  it('does not present a requested switch as serving when no implementation is registered', () => {
    const view = describeClassifierSlot(slot({ requestedEnabled: true, implementationAvailable: false }));

    expect(view.tone).toBe('requested');
    expect(view.headline).toContain('NOT IN EFFECT');
    expect(view.detail).toContain('no ready implementation has registered');
    expect(view.detail).toContain('MISSING_READY_IMPLEMENTATION');
    expect(view.effectiveEnabled).toBe(false);
  });

  it('reports a slot as effective only when the switch is on and an implementation is registered', () => {
    const view = describeClassifierSlot(
      slot({ requestedEnabled: true, implementationAvailable: true, effectiveEnabled: true, acceptingRequests: true }),
    );

    expect(view.tone).toBe('effective');
    expect(view.headline).toBe('EFFECTIVE');
    expect(view.detail).toContain('serving');
  });

  it('flags a registered implementation that is not effective as an inconsistency', () => {
    const view = describeClassifierSlot(slot({ requestedEnabled: true, implementationAvailable: true }));

    expect(view.tone).toBe('requested');
    expect(view.detail).toContain('inconsistency');
  });

  it('keeps the startup and runtime switches distinct when the slot is off', () => {
    const startupOnly = describeClassifierSlot(slot({ startupEnabled: true, requestedEnabled: false }));
    expect(startupOnly.tone).toBe('off');
    expect(startupOnly.detail).toContain('Configured on at startup');

    const bothOff = describeClassifierSlot(slot());
    expect(bothOff.detail).toContain('Off at startup and off at runtime');
  });

  it('surfaces in-flight work that is still draining', () => {
    const view = describeClassifierSlot(slot({ draining: true, inFlight: 3 }));

    expect(view.draining).toBe(true);
    expect(view.detail).toContain('3 in-flight request(s) still draining');
  });

  it('falls back to the raw id for a slot the cockpit does not know about', () => {
    const view = describeClassifierSlot(slot({ classifier: 'novel-slot' }));

    expect(view.id).toBe('novel-slot');
    expect(view.label).toBe('novel-slot');
  });
});

describe('classifier status resolution', () => {
  it('accepts the three guaranteed slots and keeps them in order', () => {
    const view = resolveClassifierStatus(threeSlots());

    expect(view.kind).toBe('READY');
    if (view.kind !== 'READY') return;
    expect(view.slots.map((entry) => entry.id)).toEqual([...EXPECTED_CLASSIFIER_IDS]);
    expect(view.slots[0].label).toBe('Intent classifier');
    expect(view.slots[1].label).toBe('Tool-risk classifier');
    expect(view.slots[2].label).toBe('Answer-quality classifier');
  });

  it('treats an empty slot list as a contract violation, not an empty configuration', () => {
    const view = resolveClassifierStatus({ classifiers: [] });

    expect(view.kind).toBe('MALFORMED');
    if (view.kind !== 'MALFORMED') return;
    expect(view.detail).toContain('omitted intent');
  });

  it('reports which slots went missing when the response is partial', () => {
    const view = resolveClassifierStatus({ classifiers: [slot({ classifier: 'intent' })] });

    expect(view.kind).toBe('MALFORMED');
    if (view.kind !== 'MALFORMED') return;
    expect(view.detail).toContain('tool-risk');
    expect(view.detail).toContain('answer-quality');
  });

  it('rejects entries that are missing required fields', () => {
    const view = resolveClassifierStatus({
      classifiers: [{ classifier: 'intent' }, slot({ classifier: 'tool-risk' }), slot({ classifier: 'answer-quality' })],
    });

    expect(view.kind).toBe('MALFORMED');
  });

  it('rejects a payload that is not a classifiers object', () => {
    expect(resolveClassifierStatus(null).kind).toBe('MALFORMED');
    expect(resolveClassifierStatus({}).kind).toBe('MALFORMED');
  });
});

describe('classifier failure resolution', () => {
  it('treats the real server 403 body as a missing internal role', () => {
    const error = new ArcApiError(403, 'Only internal API keys can control ONNX classifiers');

    expect(resolveClassifierFailure(error)).toEqual({ kind: 'FORBIDDEN' });
  });

  it('does not misreport a CSRF rejection as a role problem', () => {
    const error = new ArcApiError(403, 'CSRF token validation failed');

    const view = resolveClassifierFailure(error);
    expect(view.kind).toBe('ERROR');
    if (view.kind !== 'ERROR') return;
    expect(view.detail).toContain('CSRF');
  });

  it('distinguishes an expired session from a missing role', () => {
    expect(resolveClassifierFailure(new ArcApiError(401, 'Unauthorized'))).toEqual({
      kind: 'UNAUTHENTICATED',
    });
  });

  it('falls back to a generic error for any other failure', () => {
    const serverError = resolveClassifierFailure(new ArcApiError(500, 'Internal Server Error'));
    expect(serverError.kind).toBe('ERROR');

    const transportError = resolveClassifierFailure(new Error('Failed to fetch'));
    expect(transportError.kind).toBe('ERROR');
    if (transportError.kind !== 'ERROR') return;
    expect(transportError.detail).toBe('Failed to fetch');
  });
});

describe('classifier panel rendering', () => {
  it('always states the scope boundary it cannot cross', () => {
    const rendered = renderPanel({ kind: 'READY', slots: [] });

    expect(rendered).toContain(CLASSIFIER_SCOPE_NOTICE);
  });

  it('mentions the activation gate only inside the scope notice', () => {
    // Lower-cased on purpose: a case-sensitive count let an injected "Activation gate:" line slip
    // through, which is exactly the claim this guard exists to stop.
    const rendered = renderPanel(resolveClassifierStatus(threeSlots())).toLowerCase();

    expect(rendered.split('activation gate').length - 1).toBe(1);
    expect(rendered).not.toContain('activation approved');
    expect(rendered).not.toContain('evidence satisfied');
  });

  it('exposes exactly one control, the refresh button, and no toggle', () => {
    const rendered = renderPanel(resolveClassifierStatus(threeSlots({ requestedEnabled: true })));
    const lowered = rendered.toLowerCase();

    expect(rendered.split('<button').length - 1).toBe(1);
    expect(rendered).toContain('Refresh');
    // Word boundaries keep the "Re-enabling is rejected with MISSING_READY_IMPLEMENTATION" prose
    // from reading as a control label.
    expect(lowered).not.toMatch(/\benable\b/);
    expect(lowered).not.toMatch(/\bdisable\b/);
  });

  it('renders one card per slot with its raw facts', () => {
    const view = resolveClassifierStatus(threeSlots({ requestedEnabled: true, revision: 7 }));
    const rendered = renderPanel(view);

    expect(rendered).toContain('Intent classifier');
    expect(rendered).toContain('Tool-risk classifier');
    expect(rendered).toContain('Answer-quality classifier');
    expect(rendered).toContain('Implementation registered');
    expect(rendered).toContain('REQUESTED — NOT IN EFFECT');
  });

  it('names the role requirement instead of showing an empty status', () => {
    const rendered = renderPanel({ kind: 'FORBIDDEN' });

    expect(rendered).toContain('Internal role required');
    expect(rendered).toContain('not authorised');
    expect(rendered).toContain('permissions result');
    expect(rendered).not.toContain('Intent classifier');
  });

  it('names an expired session and an unexpected payload distinctly', () => {
    expect(renderPanel({ kind: 'UNAUTHENTICATED' })).toContain('401');

    const malformed = renderPanel({ kind: 'MALFORMED', detail: 'The response carries no classifiers array.' });
    expect(malformed).toContain('Unexpected response shape');
    expect(malformed).toContain('The response carries no classifiers array.');
  });
});
