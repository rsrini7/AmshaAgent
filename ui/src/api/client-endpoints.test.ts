import { afterEach, describe, expect, it, vi } from 'vitest';
import { ArcClient } from './client';

type EndpointCase = {
  name: string;
  invoke: (client: ArcClient) => Promise<unknown>;
  path: string;
  method?: string;
};

const endpointCases: EndpointCase[] = [
  {
    name: 'GitHub App credential and connector status',
    invoke: (client) => client.getGitHubAppCredentialStatus(),
    path: '/admin/connectors/github-readme/app-credential',
  },
  {
    name: 'GitHub documentation connector status',
    invoke: (client) => client.getGitHubDocsConnectorStatus(),
    path: '/connectors/github-readme/status',
  },
  {
    name: 'GitHub documentation connect',
    invoke: (client) => client.connectGitHubDocs(),
    path: '/connectors/github-readme/connect',
    method: 'POST',
  },
  {
    name: 'GitHub documentation refresh',
    invoke: (client) => client.refreshGitHubDocs(),
    path: '/connectors/github-readme/refresh',
    method: 'POST',
  },
  {
    name: 'GitHub documentation disconnect',
    invoke: (client) => client.disconnectGitHubDocs(),
    path: '/connectors/github-readme',
    method: 'DELETE',
  },
  { name: 'agent list', invoke: (client) => client.getAgents(), path: '/agents' },
  {
    name: 'session status',
    invoke: (client) => client.getSessionStatus('session-1', 'coordinator'),
    path: '/sessions/session-1/status/coordinator',
  },
  { name: 'pending approvals', invoke: (client) => client.getHitl(2, 25), path: '/hitl?page=2&size=25' },
  { name: 'tool list', invoke: (client) => client.getTools(), path: '/tools' },
  { name: 'MCP connections', invoke: (client) => client.getMcpConnections(), path: '/mcp/connect' },
  { name: 'MCP approvals', invoke: (client) => client.getMcpApprovals(), path: '/mcp/connect/approvals' },
  {
    name: 'MCP discovery',
    invoke: (client) => client.discoverMcp('docs', 'https://example.test/mcp', { Authorization: 'Bearer test' }),
    path: '/mcp/connect/discover',
    method: 'POST',
  },
  {
    name: 'MCP connection creation',
    invoke: (client) => client.connectMcp('docs', 'https://example.test/mcp'),
    path: '/mcp/connect',
    method: 'POST',
  },
  {
    name: 'MCP reconfiguration',
    invoke: (client) => client.reconfigureMcp('docs', 'https://example.test/new', {}, true, 3),
    path: '/mcp/connect/docs',
    method: 'PATCH',
  },
  {
    name: 'MCP import preview',
    invoke: (client) => client.previewMcpConfig(
      Object.assign(new Blob(['{}'], { type: 'application/json' }), { name: 'servers.json' }) as File,
    ),
    path: '/mcp/import/preview',
    method: 'POST',
  },
  {
    name: 'MCP import apply',
    invoke: (client) => client.applyMcpImport('operation-1', 'docs', {}, 4),
    path: '/mcp/import/apply',
    method: 'POST',
  },
  { name: 'MCP activate', invoke: (client) => client.activateMcp('docs', 5), path: '/mcp/connect/docs/activate', method: 'POST' },
  { name: 'MCP pause', invoke: (client) => client.pauseMcp('docs', 5), path: '/mcp/connect/docs/pause', method: 'POST' },
  { name: 'MCP resume', invoke: (client) => client.resumeMcp('docs', 5), path: '/mcp/connect/docs/resume', method: 'POST' },
  { name: 'MCP reconnect', invoke: (client) => client.reconnectMcp('docs', 5), path: '/mcp/connect/docs/reconnect', method: 'POST' },
  {
    name: 'MCP tool enable',
    invoke: (client) => client.enableMcpTool('docs', 'find', 5),
    path: '/mcp/connect/docs/tools/find/enable',
    method: 'POST',
  },
  {
    name: 'MCP tool disable',
    invoke: (client) => client.disableMcpTool('docs', 'find', 5),
    path: '/mcp/connect/docs/tools/find',
    method: 'DELETE',
  },
  {
    name: 'MCP approval',
    invoke: (client) => client.approveMcp('task-1'),
    path: '/mcp/connect/approvals/task-1/approve',
    method: 'POST',
  },
  {
    name: 'MCP rejection',
    invoke: (client) => client.rejectMcp('task-1'),
    path: '/mcp/connect/approvals/task-1/reject',
    method: 'POST',
  },
  { name: 'MCP disconnect', invoke: (client) => client.disconnectMcp('docs', 5), path: '/mcp/connect/docs', method: 'DELETE' },
  { name: 'MCP setup redemption', invoke: (client) => client.redeemMcpSetup('setup-token'), path: '/mcp/setup/redeem', method: 'POST' },
  { name: 'skill list', invoke: (client) => client.getSkills(), path: '/skills' },
  { name: 'bootstrap status', invoke: (client) => client.getBootstrapStatus(), path: '/onboarding/bootstrap/status' },
  { name: 'first owner claim', invoke: (client) => client.claimFirstOwner(), path: '/onboarding/bootstrap/claim', method: 'POST' },
  { name: 'local owner recovery', invoke: (client) => client.recoverLocalOwner(), path: '/onboarding/bootstrap/local-recovery', method: 'POST' },
  { name: 'active draft lookup', invoke: (client) => client.getActiveDraftId(), path: '/onboarding/drafts' },
  { name: 'draft creation', invoke: (client) => client.createOrResumeDraft(), path: '/onboarding/drafts', method: 'POST' },
  { name: 'draft lookup', invoke: (client) => client.getDraft('draft-1'), path: '/onboarding/drafts/draft-1' },
  {
    name: 'draft step update',
    invoke: (client) => client.updateDraftStep('draft-1', 'provider', { provider: 'ollama' }),
    path: '/onboarding/drafts/draft-1/steps/provider',
    method: 'PUT',
  },
  { name: 'draft commit', invoke: (client) => client.commitDraft('draft-1'), path: '/onboarding/drafts/draft-1/commit', method: 'POST' },
  { name: 'draft discard', invoke: (client) => client.discardDraft(), path: '/onboarding/drafts/discard', method: 'POST' },
  { name: 'provider catalog', invoke: (client) => client.getProviderCatalog(), path: '/onboarding/provider-catalog' },
  {
    name: 'provider model catalog',
    invoke: (client) => client.getProviderModels('openai-compatible', 'https://example.test/v1', 'provider-key'),
    path: '/onboarding/provider-catalog/openai-compatible/models?baseUrl=https%3A%2F%2Fexample.test%2Fv1',
  },
  { name: 'step descriptors', invoke: (client) => client.getStepDescriptors(), path: '/onboarding/steps/descriptors' },
  { name: 'step descriptor', invoke: (client) => client.getStepDescriptor('provider'), path: '/onboarding/steps/provider/descriptor' },
  {
    name: 'draft connection validation',
    invoke: (client) => client.validateDraftConnection('draft-1', { probeNetwork: true }),
    path: '/onboarding/drafts/draft-1/validate-connection',
    method: 'POST',
  },
  {
    name: 'draft approval resolution',
    invoke: (client) => client.resolveDraftApproval('draft-1', 'approval-1', true, 'confirmed'),
    path: '/onboarding/drafts/draft-1/approvals/approval-1/resolve',
    method: 'POST',
  },
  { name: 'model defaults', invoke: (client) => client.getModelDefaults(), path: '/model-defaults' },
  { name: 'onboarding status', invoke: (client) => client.onboardingStatus(), path: '/onboarding/status' },
  { name: 'legacy onboarding step', invoke: (client) => client.onboardingStep(3, { provider: 'ollama' }), path: '/onboarding/step/3', method: 'POST' },
  { name: 'onboarding completion', invoke: (client) => client.onboardingComplete(), path: '/onboarding/complete' },
  { name: 'settings lookup', invoke: (client) => client.getSettings(), path: '/settings' },
  { name: 'prompt update', invoke: (client) => client.patchPrompt('Be concise'), path: '/settings/prompt', method: 'PATCH' },
  { name: 'prompt lookup', invoke: (client) => client.getPrompt(), path: '/settings/prompt' },
  { name: 'voice update', invoke: (client) => client.patchVoice('alloy', 'automatic'), path: '/settings/voice', method: 'PATCH' },
  { name: 'onboarding reset', invoke: (client) => client.resetOnboarding(), path: '/settings/reset', method: 'POST' },
  { name: 'default network profile lookup', invoke: (client) => client.getConnectionNetworkProfile(), path: '/settings/connection-network-profile' },
  { name: 'indexed network profile lookup', invoke: (client) => client.getConnectionNetworkProfile(2), path: '/settings/connection-network-profile?connectionIndex=2' },
  {
    name: 'network profile update',
    invoke: (client) => client.patchConnectionNetworkProfile(2, {} as never),
    path: '/settings/connection-network-profile',
    method: 'PATCH',
  },
  { name: 'cron list', invoke: (client) => client.getCrons('ACTIVE', 2, 10), path: '/cron?page=2&size=10&status=ACTIVE' },
  { name: 'cron lookup', invoke: (client) => client.getCron('cron-1'), path: '/cron/cron-1' },
  { name: 'cron deletion', invoke: (client) => client.deleteCron('cron-1'), path: '/cron/cron-1', method: 'DELETE' },
  { name: 'cron trigger', invoke: (client) => client.triggerCron('cron-1'), path: '/cron/cron-1/trigger', method: 'POST' },
  { name: 'cron pause', invoke: (client) => client.pauseCron('cron-1'), path: '/cron/cron-1/pause', method: 'POST' },
  { name: 'cron resume', invoke: (client) => client.resumeCron('cron-1'), path: '/cron/cron-1/resume', method: 'POST' },
  { name: 'cron run list', invoke: (client) => client.getCronRuns('cron-1', 3, 7), path: '/cron/cron-1/runs?page=3&size=7' },
  {
    name: 'audit event list',
    invoke: (client) => client.getAuditEvents({ sessionId: 'session-1', eventType: 'TOOL', from: 'today', to: 'tomorrow', page: 2, size: 8 }),
    path: '/audit?page=2&size=8&sessionId=session-1&eventType=TOOL&from=today&to=tomorrow',
  },
  { name: 'audit event lookup', invoke: (client) => client.getAuditEvent('event-1'), path: '/audit/event-1' },
  { name: 'audit event types', invoke: (client) => client.getAuditEventTypes(), path: '/audit/event-types' },
  { name: 'session audit', invoke: (client) => client.getSessionAudit('session-1'), path: '/audit/session/session-1' },
  { name: 'audit chain verification', invoke: (client) => client.verifyAuditChain('session-1'), path: '/audit/session-1/verify' },
  { name: 'symbol lookup', invoke: (client) => client.symbolicate('node one'), path: '/cockpit/symbolicate?symbol=node%20one' },
  { name: 'database query', invoke: (client) => client.queryDb({ sql: 'select 1', limit: 1 }), path: '/admin/db/query', method: 'POST' },
  { name: 'arena comparison', invoke: (client) => client.compareArena({ prompt: 'hello', models: ['model-a'] }), path: '/admin/arena/compare', method: 'POST' },
  { name: 'cockpit health', invoke: (client) => client.getCockpitHealth(), path: '/admin/cockpit/health' },
];

describe('ArcClient endpoint wrappers', () => {
  afterEach(() => vi.unstubAllGlobals());

  it.each(endpointCases)('$name uses the expected endpoint and method', async ({ invoke, path, method }) => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({ items: [], connections: [], connectedServers: [], crons: [], runs: [], events: [], eventTypes: [] }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    );
    vi.stubGlobal('fetch', fetchMock);

    await invoke(new ArcClient('/api/v1'));

    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, request] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe(`/api/v1${path}`);
    expect(request.method ?? 'GET').toBe(method ?? 'GET');
  });
});
