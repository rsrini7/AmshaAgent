import { describe, expect, it } from 'vitest';
import { ArcAgentStatus } from './arc-agent-status';
import type { AgentStatusDto } from '../api/generated/models/AgentStatusDto';

function flattenLit(value: any): string {
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

describe('ArcAgentStatus', () => {
  it('renders an empty state when there are no active agents', () => {
    const component = new ArcAgentStatus();
    component.agents = [];

    expect(flattenLit(component.render())).toContain('No agents active.');
  });

  it('renders agent names, autonomy, task, status, and fallback values', () => {
    const component = new ArcAgentStatus();
    component.agents = [
      {
        agentName: 'workspace:Researcher',
        status: 'running',
        currentTask: 'Review the project docs',
        autonomyLevel: 'full',
        tokenCount: 12,
      },
      { agentName: 'Scheduler', autonomyLevel: 'custom' },
    ] satisfies AgentStatusDto[];

    const rendered = flattenLit(component.render());

    expect(rendered).toContain('Researcher');
    expect(rendered).toContain('Full');
    expect(rendered).toContain('Review the project docs');
    expect(rendered).toContain('12 TOKENS');
    expect(rendered).toContain('Scheduler');
    expect(rendered).toContain('custom');
    expect(rendered).toContain('Agent is currently idle.');
    expect(rendered).toContain('0 TOKENS');
  });
});
