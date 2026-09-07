import { describe, expect, it } from 'vitest';
import { ArcHealthDashboard } from './arc-health-dashboard';
import { ArcNav } from './arc-nav';
import { ROUTES } from '../routes';
import { CockpitHealthResponse } from '../api/client';

function flattenLit(template: any): string {
  if (template === null || template === undefined) return '';
  if (typeof template === 'string') return template;
  if (typeof template === 'number' || typeof template === 'boolean') return String(template);
  if (Array.isArray(template)) return template.map(flattenLit).join(' ');
  if (template.strings && Array.isArray(template.values)) {
    let result = '';
    for (let i = 0; i < template.strings.length; i++) {
      result += template.strings[i];
      if (i < template.values.length) {
        result += flattenLit(template.values[i]);
      }
    }
    return result;
  }
  return JSON.stringify(template);
}

describe('UI Accessibility & Health Dashboard Tests (A11y)', () => {
  const mockHealthUp: CockpitHealthResponse = {
    status: 'UP',
    healthChecks: {
      status: 'UP',
      totalChecks: 3,
      passingChecks: 3,
      checks: [
        { name: 'System Metadata', status: 'UP', data: { version: '0.1.0' } },
        { name: 'Vector Index', status: 'UP', data: { status: 'compatible' } },
        { name: 'Podman Sandboxes', status: 'UP', data: { rootless: true } },
      ],
    },
    database: {
      backend: 'sqlite',
      status: 'UP',
      poolEnabled: true,
      activeConnections: 1,
      availableConnections: 4,
      maxPoolSize: 5,
      awaitingConnections: 0,
    },
    providers: [
      {
        providerName: 'groq',
        isDefault: true,
        status: 'CONFIGURED',
        baseUrl: 'https://api.groq.com/openai/v1',
        details: 'Ready (model instantiated)',
      },
      {
        providerName: 'openai',
        isDefault: false,
        status: 'NOT_CONFIGURED',
        baseUrl: 'https://api.openai.com/v1',
        details: 'Missing OPENAI_API_KEY',
      },
    ],
    timestamp: 1725700000000,
  };

  it('verifies #/health route is registered in ROUTES with icon and label', () => {
    const healthRoute = ROUTES.find((r) => r.hash === '#/health');
    expect(healthRoute).toBeDefined();
    expect(healthRoute?.label).toBe('Health');
    expect(healthRoute?.icon).toBe('🩺');
    expect(healthRoute?.component).toBe('arc-health-dashboard');
  });

  it('renders ArcNav with accessible navigation landmarks, labels, and current page attribute', () => {
    const nav = new ArcNav();
    nav.route = '#/health';

    const rendered = flattenLit(nav.render());
    expect(rendered).toContain('role="navigation"');
    expect(rendered).toContain('aria-label="Health"');
    expect(rendered).toContain('aria-current="page"');
    expect(rendered).toContain('Collapse navigation');
  });

  it('renders ArcHealthDashboard with accessible status banner and regions', async () => {
    const dashboard = new ArcHealthDashboard();
    (dashboard as any)._health = mockHealthUp;
    (dashboard as any)._loading = false;

    const rendered = flattenLit(dashboard.render());

    // Live region status banner
    expect(rendered).toContain('role="status"');
    expect(rendered).toContain('aria-live="polite"');
    expect(rendered).toContain('Overall Status: UP');

    // Section landmarks with accessible labels
    expect(rendered).toContain('role="region"');
    expect(rendered).toContain('aria-label="MicroProfile Health Checks"');
    expect(rendered).toContain('aria-label="Database and Connection Pool"');
    expect(rendered).toContain('aria-label="LLM Provider Configuration Status"');

    // Action button accessibility
    expect(rendered).toContain('aria-pressed="true"');
    expect(rendered).toContain('aria-label="Refresh health metrics"');

    // Content verification
    expect(rendered).toContain('System Metadata');
    expect(rendered).toContain('Vector Index');
    expect(rendered).toContain('Agroal Enabled');
    expect(rendered).toContain('groq');
    expect(rendered).toContain('Default');
  });

  it('renders accessible alert role on access denied (403/401)', async () => {
    const dashboard = new ArcHealthDashboard();
    (dashboard as any)._authStatus = 403;

    const rendered = flattenLit(dashboard.render());
    expect(rendered).toContain('role="alert"');
    expect(rendered).toContain('Access Denied (403)');
    expect(rendered).toContain('Internal operator role required');
  });

  it('renders accessible alert role on network/server error', async () => {
    const dashboard = new ArcHealthDashboard();
    (dashboard as any)._error = 'Connection to gateway timed out';

    const rendered = flattenLit(dashboard.render());
    expect(rendered).toContain('role="alert"');
    expect(rendered).toContain('Failed to load health status:');
    expect(rendered).toContain('Connection to gateway timed out');
  });

  it('toggles auto-refresh and updates aria-pressed accordingly', () => {
    const dashboard = new ArcHealthDashboard();
    expect((dashboard as any)._autoRefresh).toBe(true);

    (dashboard as any)._toggleAutoRefresh();
    expect((dashboard as any)._autoRefresh).toBe(false);

    let rendered = flattenLit(dashboard.render());
    expect(rendered).toContain('aria-pressed="false"');
    expect(rendered).toContain('Auto-refresh: OFF');

    (dashboard as any)._toggleAutoRefresh();
    expect((dashboard as any)._autoRefresh).toBe(true);

    rendered = flattenLit(dashboard.render());
    expect(rendered).toContain('aria-pressed="true"');
    expect(rendered).toContain('Auto-refresh: ON');
  });

  it('fetches health data via ArcClient on refresh()', async () => {
    const dashboard = new ArcHealthDashboard();
    (dashboard as any)._appContext = { apiKey: 'admin-key' };

    const originalFetch = globalThis.fetch;
    let fetchedUrl = '';

    try {
      globalThis.fetch = async (url: any) => {
        fetchedUrl = String(url);
        return {
          ok: true,
          status: 200,
          json: async () => mockHealthUp,
          text: async () => JSON.stringify(mockHealthUp),
        } as any;
      };

      await dashboard.refresh(false);

      expect(fetchedUrl).toContain('/api/v1/admin/cockpit/health');
      expect((dashboard as any)._health).toEqual(mockHealthUp);
      expect((dashboard as any)._error).toBeNull();
    } finally {
      globalThis.fetch = originalFetch;
    }
  });
});
