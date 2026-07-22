import { AgentStatusDto } from './generated/models/AgentStatusDto';
import { SkillDto } from './generated/models/SkillDto';
import { PendingApprovalDto, ApprovalResultDto, ToolDto } from './types';

export class ArcClient {
  constructor(
    public readonly baseUrl: string,
    private readonly apiKey?: string
  ) {}

  private headers(): HeadersInit {
    const h: Record<string, string> = {
      'Content-Type': 'application/json',
    };
    if (this.apiKey && this.apiKey !== 'authenticated') {
      h['X-AMSHA-API-KEY'] = this.apiKey;
    }
    return h;
  }

  private async request<T>(endpoint: string, options?: RequestInit): Promise<T> {
    const res = await fetch(`${this.baseUrl}${endpoint}`, {
      ...options,
      credentials: 'include',
      headers: { ...this.headers(), ...options?.headers }
    });
    if (!res.ok) {
      const errorData = await res.json().catch(() => ({}));
      throw new Error(errorData.error || `Request to ${endpoint} failed with status ${res.status}`);
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

  async getSkills(): Promise<SkillDto[]> {
    return this.request<SkillDto[]>('/skills');
  }

  // Onboarding
  async getBootstrapStatus(): Promise<any> {
    return this.request<any>('/onboarding/bootstrap/status');
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
    return this.request<any>('/onboarding/status');
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
