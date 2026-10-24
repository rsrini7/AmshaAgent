import { LitElement } from 'lit';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ArcThemeToggle } from './arc-theme-toggle';

type MediaChangeListener = (event: MediaQueryListEvent) => void;

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

function clickToggle(component: ArcThemeToggle): void {
  const clickHandler = component.render().values.find((value) => typeof value === 'function');
  expect(clickHandler).toBeTypeOf('function');
  if (typeof clickHandler === 'function') clickHandler.call(component);
}

describe('ArcThemeToggle', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('follows the system theme until the user selects a theme, then persists it', () => {
    let storedTheme: string | null = null;
    const listeners: MediaChangeListener[] = [];
    const mediaQuery = {
      matches: true,
      addEventListener: vi.fn((_type: string, listener: MediaChangeListener) => listeners.push(listener)),
      removeEventListener: vi.fn(),
    };
    const documentElement = { setAttribute: vi.fn() };
    const storage = {
      getItem: vi.fn(() => storedTheme),
      setItem: vi.fn((_key: string, value: string) => {
        storedTheme = value;
      }),
    };
    vi.stubGlobal('window', { matchMedia: vi.fn(() => mediaQuery) });
    vi.stubGlobal('sessionStorage', storage);
    vi.stubGlobal('document', { documentElement });

    // The test exercises component lifecycle logic without requiring a browser DOM.
    vi.spyOn(LitElement.prototype, 'connectedCallback').mockImplementation(() => {});
    vi.spyOn(LitElement.prototype, 'disconnectedCallback').mockImplementation(() => {});
    const component = new ArcThemeToggle();
    component.connectedCallback();

    expect(documentElement.setAttribute).toHaveBeenLastCalledWith('data-theme', 'dark');
    expect(mediaQuery.addEventListener).toHaveBeenCalledWith('change', expect.any(Function));
    expect(flattenLit(component.render())).toContain('Switch to light mode');

    const onThemeChange = listeners[0];
    if (!onThemeChange) throw new Error('Theme change listener was not registered');
    onThemeChange({ matches: false } as MediaQueryListEvent);
    expect(documentElement.setAttribute).toHaveBeenLastCalledWith('data-theme', 'light');
    expect(flattenLit(component.render())).toContain('Switch to dark mode');

    clickToggle(component);
    expect(storage.setItem).toHaveBeenCalledWith('arc-theme', 'dark');
    expect(documentElement.setAttribute).toHaveBeenLastCalledWith('data-theme', 'dark');

    onThemeChange({ matches: false } as MediaQueryListEvent);
    expect(flattenLit(component.render())).toContain('Switch to light mode');

    component.disconnectedCallback();
    expect(mediaQuery.removeEventListener).toHaveBeenCalledWith('change', onThemeChange);
  });

  it('uses a saved theme instead of the system preference', () => {
    const mediaQuery = {
      matches: true,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    };
    const documentElement = { setAttribute: vi.fn() };
    vi.stubGlobal('window', { matchMedia: vi.fn(() => mediaQuery) });
    vi.stubGlobal('sessionStorage', {
      getItem: vi.fn(() => 'light'),
      setItem: vi.fn(),
    });
    vi.stubGlobal('document', { documentElement });
    vi.spyOn(LitElement.prototype, 'connectedCallback').mockImplementation(() => {});

    const component = new ArcThemeToggle();
    component.connectedCallback();

    expect(documentElement.setAttribute).toHaveBeenLastCalledWith('data-theme', 'light');
    expect(flattenLit(component.render())).toContain('Switch to dark mode');
  });
});
