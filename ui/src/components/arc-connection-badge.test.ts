import { describe, expect, it } from 'vitest';
import { SseStatus } from '../api/sse';
import { ArcConnectionBadge } from './arc-connection-badge';

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

describe('ArcConnectionBadge', () => {
  it.each([
    [SseStatus.DISCONNECTED, 'Disconnected'],
    [SseStatus.CONNECTED, 'Live'],
    [SseStatus.ERROR, 'Issue'],
    [SseStatus.UNAUTHORIZED, 'Sign in'],
  ])('labels %s connection as %s', (status, label) => {
    const component = new ArcConnectionBadge();
    component.status = status;

    const rendered = flattenLit(component.render());

    expect(rendered).toContain(`status-${status}`);
    expect(rendered).toContain(label);
  });
});
