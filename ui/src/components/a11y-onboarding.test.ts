import { describe, expect, it, vi } from 'vitest';
import { ArcOnboarding } from './arc-onboarding';
import { ArcOnboardingPlanStepper } from './onboarding/arc-onboarding-plan-stepper';
import { ArcOnboardingDescriptorRenderer } from './onboarding/arc-onboarding-descriptor-renderer';
import { ArcOnboardingStepWelcome } from './onboarding/arc-onboarding-step-welcome';
import { ArcOnboardingStepMcp } from './onboarding/arc-onboarding-step-mcp';
import { ArcClient } from '../api/client';
import { OnboardingStepDescriptorDto } from '../api/types';

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

describe('Onboarding Accessibility (A11y) & Schema-Driven Renderer Tests', () => {
  it('renders ArcOnboarding wizard with main landmark, role, and screen-reader live region', () => {
    const wizard = new ArcOnboarding();
    const rendered = flattenLit(wizard.render());

    expect(rendered).toContain('role="main"');
    expect(rendered).toContain('aria-label="Amsha Agent Infrastructure Onboarding"');
    expect(rendered).toContain('aria-live="polite"');
    expect(rendered).toContain('id="step-announcer"');
  });

  it('renders ArcOnboardingPlanStepper with accessible navigation, listitem roles, and aria-current', () => {
    const stepper = new ArcOnboardingPlanStepper();
    stepper.planStages = ['welcome', 'connections', 'routes', 'mcp', 'review'];
    stepper.currentStepId = 'connections';

    const rendered = flattenLit(stepper.render());

    expect(rendered).toContain('role="navigation"');
    expect(rendered).toContain('aria-label="Onboarding Progress"');
    expect(rendered).toContain('role="listitem"');
    expect(rendered).toContain('aria-current="step"');
    expect(rendered).toContain('Step 2: Connections (Current)');
    expect(rendered).toContain('Step 1: Welcome (Completed)');
    expect(rendered).toContain('Step 3: Routing (Upcoming)');
  });

  it('renders ArcOnboardingDescriptorRenderer with accessible form, labels, and aria attributes', () => {
    const renderer = new ArcOnboardingDescriptorRenderer();
    const descriptor: OnboardingStepDescriptorDto = {
      id: 'mcp',
      version: '1.0',
      order: 3,
      optional: true,
      title: 'Model Context Protocol (MCP)',
      description: 'Connect external tool servers to extend agent capabilities.',
      fields: [
        {
          name: 'name',
          type: 'TEXT',
          required: true,
          options: [],
          title: 'Server Name',
          description: 'Unique name identifying the MCP server'
        },
        {
          name: 'endpoint',
          type: 'URL',
          required: true,
          options: [],
          title: 'Server Endpoint URL',
          description: 'HTTP or SSE endpoint'
        },
        {
          name: 'apiKey',
          type: 'SECRET',
          required: false,
          options: [],
          title: 'API Key',
          description: 'Bearer authentication token'
        },
        {
          name: 'category',
          type: 'SELECT',
          required: true,
          options: ['INTERNAL', 'EXTERNAL', 'SANDBOX'],
          title: 'Tool Category',
          description: 'Category classification'
        },
        {
          name: 'active',
          type: 'CHECKBOX',
          required: false,
          options: [],
          title: 'Enable on startup',
          description: 'Whether to activate immediately'
        }
      ]
    };

    renderer.descriptor = descriptor;
    renderer.values = { name: 'brave-search', category: 'INTERNAL' };
    renderer.errors = { endpoint: 'Endpoint URL is required' };

    const rendered = flattenLit(renderer.render());

    // Landmark & headings
    expect(rendered).toContain('role="form"');
    expect(rendered).toContain('aria-label="Model Context Protocol (MCP)"');
    expect(rendered).toContain('id="step-heading-mcp"');
    expect(rendered).toContain('id="step-desc-mcp"');

    // Label associations
    expect(rendered).toContain('for="field-name"');
    expect(rendered).toContain('id="field-name"');
    expect(rendered).toContain('aria-required="true"');

    // Secret field has autocomplete and password type
    expect(rendered).toContain('type="password"');
    expect(rendered).toContain('autocomplete="new-password"');

    // URL field
    expect(rendered).toContain('type="url"');

    // Select with options
    expect(rendered).toContain('<option value="INTERNAL"');
    expect(rendered).toContain('<option value="EXTERNAL"');

    // Error alert & aria-invalid
    expect(rendered).toContain('role="alert"');
    expect(rendered).toContain('Endpoint URL is required');
    expect(rendered).toContain('aria-invalid="true"');

    // Accessible buttons
    expect(rendered).toContain('aria-label="Go back to previous step"');
    expect(rendered).toContain('aria-label="Continue to next step"');
  });

  it('renders ArcOnboardingStepWelcome with accessible action buttons', () => {
    const welcome = new ArcOnboardingStepWelcome();
    const rendered = flattenLit(welcome.render());

    expect(rendered).toContain('Quick Start');
    expect(rendered).toContain('Advanced Setup');
  });

  it('renders ArcOnboardingStepMcp with accessible server list and removal controls', () => {
    const mcp = new ArcOnboardingStepMcp();
    mcp.mcpServers = [
      { name: 'brave', transport: 'sse', url: 'https://brave.api/mcp' }
    ];

    const rendered = flattenLit(mcp.render());

    expect(rendered).toContain('brave');
    expect(rendered).toContain('sse');
    expect(rendered).toContain('mcp-remove');
  });

  it('verifies ArcClient exposes typed step descriptor and connection validation methods', () => {
    const client = new ArcClient('/api/v1');

    expect(typeof client.getStepDescriptors).toBe('function');
    expect(typeof client.getStepDescriptor).toBe('function');
    expect(typeof client.validateDraftConnection).toBe('function');
    expect(typeof client.resolveDraftApproval).toBe('function');
  });
});
