import { LitElement } from 'lit';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ArcClient } from '../api/client';
import type { ToolDto } from '../api/types';
import type { SkillDto } from '../api/generated/models/SkillDto';
import { ArcSkillsPanel } from './arc-skills-panel';

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

describe('ArcSkillsPanel', () => {
  afterEach(() => vi.restoreAllMocks());

  it('loads and displays skills and tools, including optional field fallbacks', async () => {
    const skills: SkillDto[] = [
      { name: 'Workspace skill', description: 'A stored skill', source: 'DB', triggerPatterns: ['research'] },
      { name: 'Built-in skill', source: 'BUILTIN', triggerPatterns: [] },
      { name: 'System skill', description: '' },
    ];
    const tools: ToolDto[] = [
      { name: 'Search', description: 'Search the web', inputSchema: '{}' },
      { name: 'Read file', description: '', inputSchema: null },
    ];
    const getSkills = vi.spyOn(ArcClient.prototype, 'getSkills').mockResolvedValue(skills);
    const getTools = vi.spyOn(ArcClient.prototype, 'getTools').mockResolvedValue(tools);
    const component = new ArcSkillsPanel();
    (component as any)._appContext = { apiKey: 'test-api-key' };

    await (component as any)._loadData();

    expect(getSkills).toHaveBeenCalledOnce();
    expect(getTools).toHaveBeenCalledOnce();
    const rendered = flattenLit(component.render());
    expect(rendered).toContain('Workspace skill');
    expect(rendered).toContain('A stored skill');
    expect(rendered).toContain('DB');
    expect(rendered).toContain('1 TRIGGER');
    expect(rendered).toContain('Built-in skill');
    expect(rendered).toContain('SYSTEM');
    expect(rendered).toContain('No description provided.');
    expect(rendered).toContain('Search');
    expect(rendered).toContain('SCHEMA');
    expect(rendered).toContain('NO SCHEMA');
  });

  it('renders empty, loading, and error states and clears loading after a failed request', async () => {
    const component = new ArcSkillsPanel();
    expect(flattenLit(component.render())).toContain('No skills registered');
    expect(flattenLit(component.render())).toContain('No system tools');

    (component as any)._loading = true;
    expect(flattenLit(component.render())).toContain('Synchronizing skills catalogue...');

    (component as any)._loading = false;
    (component as any)._error = 'Catalog unavailable';
    expect(flattenLit(component.render())).toContain('Catalog unavailable');

    vi.spyOn(ArcClient.prototype, 'getSkills').mockRejectedValue(new Error(''));
    vi.spyOn(ArcClient.prototype, 'getTools').mockResolvedValue([]);
    await (component as any)._loadData();

    expect((component as any)._error).toMatch(/Failed.*skills.*tools/);
    expect((component as any)._loading).toBe(false);
  });

  it('loads for an authenticated context and reacts to context updates', async () => {
    vi.spyOn(LitElement.prototype, 'connectedCallback').mockImplementation(() => {});
    const component = new ArcSkillsPanel();
    const loadData = vi.spyOn(component as any, '_loadData').mockResolvedValue(undefined);
    (component as any)._appContext = { apiKey: 'test-api-key' };

    await component.connectedCallback();
    component.updated(new Map([['_appContext', undefined]]));

    expect(loadData).toHaveBeenCalledTimes(2);

    (component as any)._loading = true;
    component.updated(new Map([['_appContext', undefined]]));
    expect(loadData).toHaveBeenCalledTimes(2);
  });
});
