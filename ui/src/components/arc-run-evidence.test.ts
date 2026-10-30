// @vitest-environment jsdom

import { afterEach, describe, expect, it } from 'vitest';
import { ArcRunEvidence } from './arc-run-evidence';
import { RunEvidenceDto } from '../types/trace';

function mount(evidence: RunEvidenceDto | null): ArcRunEvidence {
  const element = new ArcRunEvidence();
  element.evidence = evidence;
  document.body.append(element);
  return element;
}

describe('ArcRunEvidence', () => {
  afterEach(() => {
    document.body.replaceChildren();
  });

  it('labels missing run measurements and decisions unavailable', async () => {
    const element = mount(null);
    await element.updateComplete;
    const root = element.shadowRoot;
    const text = root?.textContent ?? '';

    expect(Array.from(root?.querySelectorAll('dl div dd') ?? []).map((item) => item.textContent?.trim()))
      .toEqual(['Unavailable', 'Unavailable', 'Unavailable', 'Unavailable']);
    expect(text).toContain('Guard decisions');
    expect(text).toContain('Approval decisions');
  });

  it('labels decision groups unavailable when the API reports zero observed events', async () => {
    const evidence: RunEvidenceDto = {
      runId: 'session-1:turn-4',
      outcomeStatus: 'UNAVAILABLE',
      outcomeAvailability: 'UNAVAILABLE',
      latencyMs: null,
      latencyAvailability: 'UNAVAILABLE',
      observedEventCount: 0,
      eventsTruncated: false,
      guardDecisions: [],
      approvalDecisions: [],
      decisionEvidenceTruncated: false,
      promptTokens: null,
      completionTokens: null,
      usageAvailability: 'UNAVAILABLE',
      costStatus: 'UNAVAILABLE',
      estimatedCost: null,
      costCurrency: null,
      costReason: 'No correlated run trace evidence available.',
    };
    const element = mount(evidence);
    await element.updateComplete;
    const groups = Array.from(element.shadowRoot?.querySelectorAll('.decision-groups p') ?? []);

    expect(groups.map((item) => item.textContent?.trim())).toEqual(['Unavailable', 'Unavailable']);
  });

  it('renders measured evidence while ignoring untrusted extra fields', async () => {
    const evidence: RunEvidenceDto & { detail?: string } = {
      runId: 'session-1:turn-3',
      outcomeStatus: 'END',
      outcomeAvailability: 'OBSERVED',
      latencyMs: 83,
      latencyAvailability: 'MEASURED',
      observedEventCount: 5,
      eventsTruncated: false,
      guardDecisions: [{ decision: 'ALLOW', riskLevel: 'HIGH', timestamp: '2026-10-29T00:00:00Z' }],
      approvalDecisions: [{ decision: 'DENIED', riskLevel: null, timestamp: '2026-10-29T00:00:01Z' }],
      decisionEvidenceTruncated: false,
      promptTokens: 12,
      completionTokens: 7,
      usageAvailability: 'OBSERVED',
      costStatus: 'UNPRICED',
      estimatedCost: null,
      costCurrency: null,
      costReason: 'safe fixed explanation',
      detail: 'DO_NOT_RENDER_SECRET_SENTINEL',
    };
    const element = mount(evidence);
    await element.updateComplete;
    const text = element.shadowRoot?.textContent ?? '';

    expect(text).toContain('END');
    expect(text).toContain('83 ms');
    expect(text).toContain('Unpriced');
    expect(text).toContain('Prompt 12');
    expect(text).toContain('Completion 7');
    expect(text).toContain('ALLOW');
    expect(text).toContain('DENIED');
    expect(text).not.toContain('DO_NOT_RENDER_SECRET_SENTINEL');
    expect(text).not.toContain('safe fixed explanation');
  });
});
