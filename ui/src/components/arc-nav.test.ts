import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ArcNav } from './arc-nav';
import { ROUTES } from '../routes';

class MockStorage implements Storage {
  private store = new Map<string, string>();
  get length() {
    return this.store.size;
  }
  clear() {
    this.store.clear();
  }
  getItem(key: string) {
    return this.store.get(key) ?? null;
  }
  key(index: number) {
    return Array.from(this.store.keys())[index] ?? null;
  }
  removeItem(key: string) {
    this.store.delete(key);
  }
  setItem(key: string, val: string) {
    this.store.set(key, val);
  }
}

describe('ArcNav collapsible sidebar', () => {
  let storage: MockStorage;

  beforeEach(() => {
    storage = new MockStorage();
    vi.stubGlobal('localStorage', storage);
  });

  it('renders all routes and labels in expanded mode by default', () => {
    const nav = new ArcNav();
    expect(nav.collapsed).toBe(false);

    const rendered = JSON.stringify(nav.render());
    expect(rendered).toContain('Amsha Agent');
    for (const r of ROUTES) {
      expect(rendered).toContain(r.label);
      expect(rendered).toContain(r.icon);
    }
  });

  it('toggles collapsed state and updates localStorage', () => {
    const nav = new ArcNav();
    const eventStates: boolean[] = [];
    nav.addEventListener('nav-collapse-changed', ((e: CustomEvent) => {
      eventStates.push(e.detail.collapsed);
    }) as EventListener);

    (nav as any)._toggleCollapse();
    expect(nav.collapsed).toBe(true);
    expect(eventStates).toEqual([true]);
    expect(storage.getItem('amsha-nav-collapsed')).toBe('true');

    // Toggle back to expanded
    (nav as any)._toggleCollapse();
    expect(nav.collapsed).toBe(false);
    expect(eventStates).toEqual([true, false]);
    expect(storage.getItem('amsha-nav-collapsed')).toBe('false');
  });

  it('provides mouse-over tooltips and accessible labels for all routes when collapsed', () => {
    const nav = new ArcNav();
    nav.collapsed = true;

    const rendered = JSON.stringify(nav.render());
    // In collapsed mode, link-label span is not rendered
    expect(rendered).not.toContain('link-label');
    // But title, aria-label, and icon values are present in template
    for (const r of ROUTES) {
      expect(rendered).toContain(r.label);
      expect(rendered).toContain(r.icon);
    }
  });

  it('restores collapsed state from localStorage on connect', () => {
    storage.setItem('amsha-nav-collapsed', 'true');
    const nav = new ArcNav();
    nav._restoreCollapsedPreference();
    expect(nav.collapsed).toBe(true);
  });
});
