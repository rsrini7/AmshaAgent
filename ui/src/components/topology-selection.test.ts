import { describe, expect, it } from 'vitest';
import { resolveTopologyView } from './arc-topology-graph';

describe('Topology Selection Logic', () => {
  it('selects ReAct view when backend paradigm metadata is REACT', () => {
    const view = resolveTopologyView('REACT');
    expect(view).toBe('react');

    const viewLower = resolveTopologyView('react');
    expect(viewLower).toBe('react');
  });

  it('selects Multi-Agent view when backend paradigm metadata is MULTI_AGENT', () => {
    const view = resolveTopologyView('MULTI_AGENT');
    expect(view).toBe('multi_agent');

    const viewAlt = resolveTopologyView('GROUP_CONSULTATION');
    expect(viewAlt).toBe('multi_agent');
  });

  it('falls back to conveyor when paradigm metadata is absent or empty', () => {
    expect(resolveTopologyView(null)).toBe('conveyor');
    expect(resolveTopologyView(undefined)).toBe('conveyor');
    expect(resolveTopologyView('')).toBe('conveyor');
    expect(resolveTopologyView('   ')).toBe('conveyor');
  });

  it('falls back to conveyor when paradigm metadata is inconsistent or unknown', () => {
    expect(resolveTopologyView('UNKNOWN_PARADIGM')).toBe('conveyor');
    expect(resolveTopologyView('PLAN_EXECUTE')).toBe('conveyor');
    expect(resolveTopologyView('REFLEXION')).toBe('conveyor');
  });

  it('honors manual requestedMode when explicitly specified', () => {
    expect(resolveTopologyView('REACT', 'multi_agent')).toBe('multi_agent');
    expect(resolveTopologyView('MULTI_AGENT', 'react')).toBe('react');
  });
});
