import { describe, expect, it } from 'vitest';
import type { AgentTraceEvent, TraceStage } from '../types/trace';
import { ArcTopologyGraph, resolveTopologyView } from './arc-topology-graph';

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

function trace(stage: TraceStage, detail: string | null = null): AgentTraceEvent {
  return {
    eventId: `event-${stage}`,
    runId: 'run-test',
    sessionId: 'session-test',
    turnNumber: 1,
    sequence: 1,
    stage,
    station: null,
    status: 'COMPLETED',
    riskLevel: null,
    detail,
    metrics: null,
    timestamp: '2026-10-24T00:00:00Z',
    eventType: 'AGENT_TRACE',
    version: 1,
  };
}

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

  it('renders a ReAct view from observed reasoning, guard, and tool events', () => {
    const graph = new ArcTopologyGraph();
    graph.paradigm = 'REACT';
    graph.viewMode = 'react';
    graph.events = [trace('llm_reasoning'), trace('guard_check'), trace('tool_end')];

    const rendered = flattenLit(graph.render());
    expect(rendered).toContain('ReAct Topology View');
    expect(rendered).toContain('Thought (Reasoning)');
    expect(rendered).toContain('Action (Guard & Invocation)');
    expect(rendered).toContain('Observation (Tool Result)');
    expect(rendered).toContain('COMPLETED');
  });

  it('renders the multi-agent stages only when matching trace events exist', () => {
    const graph = new ArcTopologyGraph();
    graph.paradigm = 'MULTI_AGENT';
    graph.viewMode = 'multi_agent';
    graph.events = [trace('route'), trace('tool_start', 'delegate to researcher'), trace('quality_check')];

    const rendered = flattenLit(graph.render());
    expect(rendered).toContain('Multi-Agent Topology View');
    expect(rendered).toContain('Orchestrator / Triage');
    expect(rendered).toContain('Specialist Delegates');
    expect(rendered).toContain('Aggregator / Quality');
    expect(rendered).toContain('COMPLETED');
  });

  it('keeps unobserved ReAct stages idle and preserves the conveyor fallback', () => {
    const react = new ArcTopologyGraph();
    react.paradigm = 'REACT';
    react.viewMode = 'react';
    expect(flattenLit(react.render())).toContain('status="IDLE"');

    const fallback = new ArcTopologyGraph();
    fallback.paradigm = 'REFLEXION';
    fallback.viewMode = 'react';
    expect(flattenLit(fallback.render())).toContain('<arc-harness-conveyor');
  });
});
