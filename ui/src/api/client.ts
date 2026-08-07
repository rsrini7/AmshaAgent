import { AgentStatusDto } from './generated/models/AgentStatusDto';
import { SkillDto } from './generated/models/SkillDto';
import { PendingApprovalDto, ApprovalResultDto, ToolDto } from './types';

export class ArcApiError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
    Object.setPrototypeOf(this, new.target.prototype);
    this.name = 'ArcApiError';
  }
}

export const OWNER_API_KEY_STORAGE_KEY = 'amsha-owner-api-key';

export function readPersistedOwnerApiKey(): string {
  if (typeof localStorage === 'undefined') return '';
  try {
    return localStorage.getItem(OWNER_API_KEY_STORAGE_KEY)?.trim() || '';
  } catch {
    return '';
  }
}

export function persistOwnerApiKey(apiKey: string): void {
  if (typeof localStorage === 'undefined' || !apiKey.trim()) return;
  try {
    localStorage.setItem(OWNER_API_KEY_STORAGE_KEY, apiKey.trim());
  } catch {
    // The HttpOnly cookie remains the primary credential when storage is blocked.
  }
}

export function clearPersistedOwnerApiKey(expectedApiKey?: string): void {
  if (typeof localStorage === 'undefined') return;
  try {
    if (expectedApiKey !== undefined) {
      const currentApiKey = localStorage.getItem(OWNER_API_KEY_STORAGE_KEY)?.trim() || '';
      if (currentApiKey !== expectedApiKey.trim()) return;
    }
    localStorage.removeItem(OWNER_API_KEY_STORAGE_KEY);
  } catch {
    // Ignore storage cleanup failures; cookie authentication is independent.
  }
}

export interface McpToolPolicy {
  id: string;
  toolName: string;
  available?: boolean;
  enabled: boolean;
  description: string;
  inputSchema: string;
  revision: number;
}

export interface McpConnection {
  name: string;
  endpoint: string;
  state: string;
  transport: string;
  enabledToolCount: number;
  discoveredToolCount: number;
  revision: number;
  tools: McpToolPolicy[];
  headers?: Record<string, string>;
}

export interface McpDiscoveryPreview {
  name: string;
  endpoint: string;
  transport: string;
  tools: Array<{ name: string; description: string; inputSchema: string; schemaHash: string }>;
}

export interface McpImportEntry {
  name: string;
  endpoint: string;
  transport: string;
  supported: boolean;
  reason: string;
}

export interface McpImportPreview {
  operationId: string;
  fileName: string;
  entries: McpImportEntry[];
  expiresAt: string;
}

export interface McpPendingApproval {
  taskId: string;
  operation: string;
  name: string;
  endpoint: string;
  toolName?: string;
  expiresAt: string;
  expectedRevision?: number;
}

export interface McpApprovalExecution<T = McpConnection | McpDiscoveryPreview> {
  taskId: string;
  operation: string;
  result: T;
}

export class ArcClient {
  constructor(
    public readonly baseUrl: string,
    private readonly apiKey?: string,
    private readonly includePersistedOwnerApiKey = true,
  ) {}

  private headers(): HeadersInit {
    const h: Record<string, string> = {
      'Content-Type': 'application/json',
    };
    const platformId = sessionStorage.getItem('amsha-platform-id');
    if (platformId) {
      h['X-AMSHA-PLATFORM-ID'] = platformId;
    }
    const persistedOwnerApiKey = this.includePersistedOwnerApiKey ? readPersistedOwnerApiKey() : '';
    const effectiveApiKey =
      this.apiKey && this.apiKey !== 'authenticated' ? this.apiKey : persistedOwnerApiKey;
    if (effectiveApiKey && effectiveApiKey !== 'authenticated') {
      h['X-AMSHA-API-KEY'] = effectiveApiKey;
    }
    return h;
  }

  private revisionHeaders(revision?: number): HeadersInit {
    return revision === undefined ? {} : { 'If-MCP-Revision': String(revision) };
  }

  private async request<T>(endpoint: string, options?: RequestInit): Promise<T> {
    const headers = new Headers(this.headers());
    new Headers(options?.headers).forEach((value, key) => headers.set(key, value));
    if (options?.body instanceof FormData) headers.delete('Content-Type');
    const res = await fetch(`${this.baseUrl}${endpoint}`, {
      ...options,
      credentials: 'include',
      headers,
    });
    if (!res.ok) {
      const errorData = await res.json().catch(() => ({}));
        throw new ArcApiError(
          res.status,
          errorData.error || `Request to ${endpoint} failed with status ${res.status}`,
        );
    }
    if (res.status === 204) {
      return {} as T;
    }
    const text = await res.text();
    return text ? JSON.parse(text) : {} as T;
  }

  async getAgents(): Promise<AgentStatusDto[]> {
    return this.request<AgentStatusDto[]>('/agents');
  }

  async getHitl(page = 0, size = 20): Promise<{ items: PendingApprovalDto[]; page: number; size: number; totalCount: number; totalPages: number }> {
    return this.request<{ items: PendingApprovalDto[]; page: number; size: number; totalCount: number; totalPages: number }>(`/hitl?page=${page}&size=${size}`);
  }

  async approveHitl(taskId: string): Promise<ApprovalResultDto> {
    return this.request<ApprovalResultDto>(`/hitl/${taskId}/approve`, {
      method: 'POST',
    });
  }

  async rejectHitl(taskId: string, reason: string): Promise<ApprovalResultDto> {
    return this.request<ApprovalResultDto>(`/hitl/${taskId}/reject`, {
      method: 'POST',
      body: JSON.stringify({ reason: reason || 'Rejected by user' }),
    });
  }

  async getTools(): Promise<ToolDto[]> {
    return this.request<ToolDto[]>('/tools');
  }

  async getMcpConnections(): Promise<McpConnection[]> {
    const result = await this.request<{ connections?: McpConnection[]; connectedServers?: McpConnection[] }>('/mcp/connect');
    return result.connections || result.connectedServers || [];
  }

  async getMcpApprovals(): Promise<McpPendingApproval[]> {
    return this.request<McpPendingApproval[]>('/mcp/connect/approvals');
  }

  async discoverMcp(name: string, url: string, headers: Record<string, string> = {}): Promise<McpDiscoveryPreview | McpPendingApproval> {
    return this.request<McpDiscoveryPreview | McpPendingApproval>('/mcp/connect/discover', {
      method: 'POST',
      body: JSON.stringify({ name, url, headers }),
    });
  }

  async connectMcp(name: string, url: string, headers: Record<string, string> = {}): Promise<McpConnection | McpPendingApproval> {
    return this.request<McpConnection | McpPendingApproval>('/mcp/connect', {
      method: 'POST',
      body: JSON.stringify({ name, url, headers }),
    });
  }

  async reconfigureMcp(
    name: string,
    url: string,
    headers: Record<string, string> = {},
    clearCredentials = false,
    revision?: number
  ): Promise<McpConnection | McpPendingApproval> {
    return this.request<McpConnection | McpPendingApproval>(`/mcp/connect/${encodeURIComponent(name)}`, {
      method: 'PATCH',
      headers: { ...this.headers(), ...this.revisionHeaders(revision) },
      body: JSON.stringify({ url, headers, clearCredentials }),
    });
  }

  async previewMcpConfig(file: File): Promise<McpImportPreview> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.request<McpImportPreview>('/mcp/import/preview', {
      method: 'POST',
      body: form,
    });
  }

  async applyMcpImport(
    operationId: string,
    name: string,
    headers: Record<string, string> = {},
    revision?: number
  ): Promise<McpPendingApproval> {
    return this.request<McpPendingApproval>('/mcp/import/apply', {
      method: 'POST',
      headers: this.revisionHeaders(revision),
      body: JSON.stringify({ operationId, name, headers }),
    });
  }

  async activateMcp(name: string, revision?: number): Promise<McpConnection | McpPendingApproval> {
    return this.request<McpConnection | McpPendingApproval>(`/mcp/connect/${encodeURIComponent(name)}/activate`, { method: 'POST', headers: this.revisionHeaders(revision) });
  }

  async pauseMcp(name: string, revision?: number): Promise<McpConnection> {
    return this.request<McpConnection>(`/mcp/connect/${encodeURIComponent(name)}/pause`, { method: 'POST', headers: this.revisionHeaders(revision) });
  }

  async resumeMcp(name: string, revision?: number): Promise<McpConnection | McpPendingApproval> {
    return this.request<McpConnection | McpPendingApproval>(`/mcp/connect/${encodeURIComponent(name)}/resume`, { method: 'POST', headers: this.revisionHeaders(revision) });
  }

  async reconnectMcp(name: string, revision?: number): Promise<McpConnection | McpPendingApproval> {
    return this.request<McpConnection | McpPendingApproval>(`/mcp/connect/${encodeURIComponent(name)}/reconnect`, { method: 'POST', headers: this.revisionHeaders(revision) });
  }

  async enableMcpTool(name: string, toolName: string, revision?: number): Promise<McpConnection | McpPendingApproval> {
    return this.request<McpConnection | McpPendingApproval>(`/mcp/connect/${encodeURIComponent(name)}/tools/${encodeURIComponent(toolName)}/enable`, { method: 'POST', headers: this.revisionHeaders(revision) });
  }

  async disableMcpTool(name: string, toolName: string, revision?: number): Promise<McpConnection> {
    return this.request<McpConnection>(`/mcp/connect/${encodeURIComponent(name)}/tools/${encodeURIComponent(toolName)}`, { method: 'DELETE', headers: this.revisionHeaders(revision) });
  }

  async approveMcp(taskId: string): Promise<McpApprovalExecution> {
    return this.request<McpApprovalExecution>(`/mcp/connect/approvals/${encodeURIComponent(taskId)}/approve`, { method: 'POST' });
  }

  async rejectMcp(taskId: string): Promise<void> {
    await this.request<void>(`/mcp/connect/approvals/${encodeURIComponent(taskId)}/reject`, { method: 'POST' });
  }

  async disconnectMcp(name: string, revision?: number): Promise<void> {
    await this.request<void>(`/mcp/connect/${encodeURIComponent(name)}`, { method: 'DELETE', headers: this.revisionHeaders(revision) });
  }

  async redeemMcpSetup(token: string): Promise<{ serverName: string; channelType: string; mode?: string; expiresAt: string }> {
    return this.request<{ serverName: string; channelType: string; mode?: string; expiresAt: string }>('/mcp/setup/redeem', {
      method: 'POST',
      body: JSON.stringify({ token }),
    });
  }

  async getSkills(): Promise<SkillDto[]> {
    return this.request<SkillDto[]>('/skills');
  }

  // Onboarding
  async getBootstrapStatus(): Promise<any> {
    return this.request<any>('/onboarding/bootstrap/status', { cache: 'no-store' });
  }

  async claimFirstOwner(): Promise<any> {
    return this.request<any>('/onboarding/bootstrap/claim', {
      method: 'POST'
    });
  }

  async getActiveDraftId(): Promise<string> {
    return this.request<string>('/onboarding/drafts');
  }

  async createOrResumeDraft(): Promise<any> {
    return this.request<any>('/onboarding/drafts', { method: 'POST' });
  }

  async getDraft(draftId: string): Promise<any> {
    return this.request<any>(`/onboarding/drafts/${draftId}`);
  }

  async updateDraftStep(draftId: string, stepId: string, payload: any): Promise<any> {
    return this.request<any>(`/onboarding/drafts/${draftId}/steps/${stepId}`, {
      method: 'PUT',
      body: JSON.stringify(payload)
    });
  }

  async commitDraft(draftId: string): Promise<any> {
    return this.request<any>(`/onboarding/drafts/${draftId}/commit`, { method: 'POST' });
  }

  async discardDraft(): Promise<any> {
    return this.request<any>('/onboarding/drafts/discard', { method: 'POST' });
  }

  async getProviderCatalog(): Promise<any> {
    return this.request<any>('/onboarding/provider-catalog');
  }

  async getModelDefaults(): Promise<Record<string, { defaultModel: string; defaultBaseUrl?: string }>> {
    return this.request<Record<string, { defaultModel: string; defaultBaseUrl?: string }>>('/model-defaults');
  }

  // Deprecated legacy facade methods (retained for backward compatibility during migration)
  async onboardingStatus(): Promise<any> {
    return this.request<any>('/onboarding/status', { cache: 'no-store' });
  }

  async onboardingStep(step: number, body?: object): Promise<any> {
    return this.request<any>(`/onboarding/step/${step}`, {
      method: 'POST',
      body: body ? JSON.stringify(body) : undefined,
    });
  }

  async onboardingComplete(): Promise<any> {
    return this.request<any>('/onboarding/complete');
  }

  // Settings
  async getSettings(): Promise<any> {
    return this.request<any>('/settings');
  }

  async patchPrompt(prompt: string): Promise<any> {
    return this.request<any>('/settings/prompt', {
      method: 'PATCH',
      body: JSON.stringify({ systemPrompt: prompt })
    });
  }

  async getPrompt(): Promise<{ systemPrompt: string }> {
    return this.request<{ systemPrompt: string }>('/settings/prompt');
  }

  async resetOnboarding(): Promise<any> {
    return this.request<any>('/settings/reset', {
      method: 'POST'
    });
  }

  // Scheduler (Cron)
  async getCrons(status?: string, page = 0, size = 50): Promise<{ crons: any[]; total: number; page: number; size: number }> {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (status) params.set('status', status);
    return this.request<{ crons: any[]; total: number; page: number; size: number }>(`/cron?${params}`);
  }

  async getCron(id: string): Promise<any> {
    return this.request<any>(`/cron/${id}`);
  }

  async deleteCron(id: string): Promise<any> {
    return this.request<any>(`/cron/${id}`, {
      method: 'DELETE',
    });
  }

  async triggerCron(id: string): Promise<any> {
    return this.request<any>(`/cron/${id}/trigger`, {
      method: 'POST',
    });
  }

  async pauseCron(id: string): Promise<any> {
    return this.request<any>(`/cron/${id}/pause`, {
      method: 'POST',
    });
  }

  async resumeCron(id: string): Promise<any> {
    return this.request<any>(`/cron/${id}/resume`, {
      method: 'POST',
    });
  }

  async getCronRuns(id: string, page = 0, size = 20): Promise<{ runs: any[]; total: number; page: number; size: number }> {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    return this.request<{ runs: any[]; total: number; page: number; size: number }>(`/cron/${id}/runs?${params}`);
  }

  // Audit
  async getAuditEvents(filters?: {
    sessionId?: string;
    eventType?: string;
    from?: string;
    to?: string;
    page?: number;
    size?: number;
  }): Promise<{ events: any[]; total: number; page: number; size: number }> {
    const params = new URLSearchParams({
      page: String(filters?.page ?? 0),
      size: String(filters?.size ?? 50),
    });
    if (filters?.sessionId) params.set('sessionId', filters.sessionId);
    if (filters?.eventType) params.set('eventType', filters.eventType);
    if (filters?.from) params.set('from', filters.from);
    if (filters?.to) params.set('to', filters.to);
    return this.request<{ events: any[]; total: number; page: number; size: number }>(`/audit?${params}`);
  }

  async getAuditEvent(id: string): Promise<any> {
    return this.request<any>(`/audit/${id}`);
  }

  async getAuditEventTypes(): Promise<{ eventTypes: string[] }> {
    return this.request<{ eventTypes: string[] }>('/audit/event-types');
  }

  async getSessionAudit(sessionId: string): Promise<any[]> {
    return this.request<any[]>(`/audit/session/${sessionId}`);
  }

  async verifyAuditChain(sessionId: string): Promise<any> {
    return this.request<any>(`/audit/${sessionId}/verify`);
  }
}

export async function getOnboardingComplete(baseUrl: string): Promise<import('./types').OnboardingCompleteDto> {
  const res = await fetch(`${baseUrl}/onboarding/complete`, {
    credentials: 'include'
  });
  if (!res.ok) throw new Error(`complete failed: ${res.status}`);
  return res.json();
}
