import { describe, expect, it } from 'vitest';
import {
  isParadigmSupported,
  resolveTopologyView,
  SUPPORTED_PARADIGMS,
} from './arc-topology-graph';

describe('Topology No-Fabrication Constraint (Issue 06 AC1, AC2)', () => {
  it('strictly limits confirmed paradigms to REACT and MULTI_AGENT in MVP', () => {
    expect(SUPPORTED_PARADIGMS).toEqual(['REACT', 'MULTI_AGENT']);
    expect(isParadigmSupported('REACT')).toBe(true);
    expect(isParadigmSupported('MULTI_AGENT')).toBe(true);
    expect(isParadigmSupported('GROUP_CONSULTATION')).toBe(true);
  });

  it('rejects unconfirmed paradigms (PLAN_EXECUTE, REFLEXION) and falls back to conveyor', () => {
    // Plan-Execute and Reflexion are explicitly deferred to Issue 07 (Evidence required)
    expect(isParadigmSupported('PLAN_EXECUTE')).toBe(false);
    expect(isParadigmSupported('REFLEXION')).toBe(false);
    expect(isParadigmSupported('AUTONOMOUS_LOOP')).toBe(false);
    expect(isParadigmSupported('SINGLE_TURN')).toBe(false);

    // Resolving unconfirmed paradigms must fall back to conveyor rather than inventing a view
    expect(resolveTopologyView('PLAN_EXECUTE')).toBe('conveyor');
    expect(resolveTopologyView('REFLEXION')).toBe('conveyor');
    expect(resolveTopologyView('AUTONOMOUS_LOOP')).toBe('conveyor');
  });
});
