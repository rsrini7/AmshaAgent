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
    if (this.apiKey) {
      h['X-ARCCLAW-API-KEY'] = this.apiKey;
    }
    return h;
  }

  async getAgents(): Promise<AgentStatusDto[]> {
    const r = await fetch(`${this.baseUrl}/agents`, { headers: this.headers() });
    if (!r.ok) throw new Error(`GET /agents failed: ${r.status}`);
    return r.json();
  }

  async getHitl(page = 0, size = 20): Promise<{ items: PendingApprovalDto[]; page: number; size: number; totalCount: number; totalPages: number }> {
    const r = await fetch(`${this.baseUrl}/hitl?page=${page}&size=${size}`, { headers: this.headers() });
    if (!r.ok) throw new Error(`GET /hitl failed: ${r.status}`);
    return r.json();
  }

  async approveHitl(taskId: string): Promise<ApprovalResultDto> {
    const r = await fetch(`${this.baseUrl}/hitl/${taskId}/approve`, {
      method: 'POST',
      headers: this.headers(),
    });
    if (!r.ok) throw new Error(`POST /hitl/${taskId}/approve failed: ${r.status}`);
    return r.json();
  }

  async rejectHitl(taskId: string, reason: string): Promise<ApprovalResultDto> {
    const r = await fetch(`${this.baseUrl}/hitl/${taskId}/reject`, {
        method: 'POST',
        headers: this.headers(),  // application/json
        body: JSON.stringify({ reason: reason || 'Rejected by user' }),
    });
    if (!r.ok) throw new Error(`POST /hitl/${taskId}/reject failed: ${r.status}`);
    return r.json();
  }

  async getTools(): Promise<ToolDto[]> {
    const r = await fetch(`${this.baseUrl}/tools`, { headers: this.headers() });
    if (!r.ok) throw new Error(`GET /tools failed: ${r.status}`);
    return r.json();
  }

  async getSkills(): Promise<SkillDto[]> {
    const r = await fetch(this.baseUrl + '/skills', { headers: this.headers() });
    if (!r.ok) throw new Error(`GET skills failed ${r.status}`);
    return r.json();
  }

  // Onboarding
  async onboardingStatus(): Promise<import('./types').OnboardingStatusDto> {
    const res = await fetch(`${this.baseUrl}/onboarding/status`, { credentials: 'include' });
    if (!res.ok) throw new Error(`status ${res.status}`);
    return res.json();
  }

  async onboardingStep(step: number, body?: object): Promise<any> {
    const res = await fetch(`${this.baseUrl}/onboarding/step/${step}`, {
      method: 'POST',
      credentials: 'include',
      headers: body ? { 'Content-Type': 'application/json' } : {},
      body: body ? JSON.stringify(body) : undefined,
    });
    if (!res.ok) {
      const errorData = await res.json().catch(() => ({}));
      throw new Error(errorData.error || `Step ${step} failed: ${res.status}`);
    }
    return res.json();
  }

  async onboardingComplete(): Promise<import('./types').OnboardingCompleteDto> {
    const res = await fetch(`${this.baseUrl}/onboarding/complete`, { credentials: 'include' });
    if (!res.ok) throw new Error(`complete failed: ${res.status}`);
    return res.json();
  }

  // Settings
  async getSettings(): Promise<any> {
    const res = await fetch(`${this.baseUrl}/settings`, {
      headers: this.headers()
    });
    if (!res.ok) throw new Error(`settings ${res.status}`);
    return res.json();
  }

  async patchProvider(body: object): Promise<any> {
    const res = await fetch(`${this.baseUrl}/settings/provider`, {
      method: 'PATCH',
      headers: this.headers(),
      body: JSON.stringify(body)
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.error || `PATCH failed: ${res.status}`);
    }
    return res.json();
  }

  async patchPrompt(prompt: string): Promise<any> {
    const res = await fetch(`${this.baseUrl}/settings/prompt`, {
      method: 'PATCH',
      headers: this.headers(),
      body: JSON.stringify({ systemPrompt: prompt })
    });
    if (!res.ok) throw new Error(`prompt update failed`);
    return res.json();
  }

  async resetOnboarding(): Promise<any> {
    const res = await fetch(`${this.baseUrl}/settings/reset`, {
      method: 'POST',
      headers: this.headers()
    });
    if (!res.ok) throw new Error(`reset failed`);
    return res.json();
  }

  // Scheduler (Cron)
  async getCrons(status?: string, page = 0, size = 50): Promise<{ crons: any[]; total: number; page: number; size: number }> {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (status) params.set('status', status);
    const r = await fetch(`${this.baseUrl}/cron?${params}`, { headers: this.headers() });
    if (!r.ok) throw new Error(`GET /cron failed: ${r.status}`);
    return r.json();
  }

  async getCron(id: string): Promise<any> {
    const r = await fetch(`${this.baseUrl}/cron/${id}`, { headers: this.headers() });
    if (!r.ok) throw new Error(`GET /cron/${id} failed: ${r.status}`);
    return r.json();
  }

  async deleteCron(id: string): Promise<any> {
    const r = await fetch(`${this.baseUrl}/cron/${id}`, {
      method: 'DELETE',
      headers: this.headers(),
    });
    if (!r.ok) throw new Error(`DELETE /cron/${id} failed: ${r.status}`);
    return r.json();
  }

  async triggerCron(id: string): Promise<any> {
    const r = await fetch(`${this.baseUrl}/cron/${id}/trigger`, {
      method: 'POST',
      headers: this.headers(),
    });
    if (!r.ok) throw new Error(`POST /cron/${id}/trigger failed: ${r.status}`);
    return r.json();
  }

  async pauseCron(id: string): Promise<any> {
    const r = await fetch(`${this.baseUrl}/cron/${id}/pause`, {
      method: 'POST',
      headers: this.headers(),
    });
    if (!r.ok) throw new Error(`POST /cron/${id}/pause failed: ${r.status}`);
    return r.json();
  }

  async resumeCron(id: string): Promise<any> {
    const r = await fetch(`${this.baseUrl}/cron/${id}/resume`, {
      method: 'POST',
      headers: this.headers(),
    });
    if (!r.ok) throw new Error(`POST /cron/${id}/resume failed: ${r.status}`);
    return r.json();
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
    const r = await fetch(`${this.baseUrl}/audit?${params}`, { headers: this.headers() });
    if (!r.ok) throw new Error(`GET /audit failed: ${r.status}`);
    return r.json();
  }

  async getAuditEvent(id: string): Promise<any> {
    const r = await fetch(`${this.baseUrl}/audit/${id}`, { headers: this.headers() });
    if (!r.ok) throw new Error(`GET /audit/${id} failed: ${r.status}`);
    return r.json();
  }

  async getSessionAudit(sessionId: string): Promise<any[]> {
    const r = await fetch(`${this.baseUrl}/audit/session/${sessionId}`, { headers: this.headers() });
    if (!r.ok) throw new Error(`GET /audit/session/${sessionId} failed: ${r.status}`);
    return r.json();
  }

  async verifyAuditChain(sessionId: string): Promise<any> {
    const r = await fetch(`${this.baseUrl}/audit/${sessionId}/verify`, { headers: this.headers() });
    if (!r.ok) throw new Error(`GET /audit/${sessionId}/verify failed: ${r.status}`);
    return r.json();
  }
}

export async function getOnboardingComplete(baseUrl: string): Promise<import('./types').OnboardingCompleteDto> {
  const res = await fetch(`${baseUrl}/onboarding/complete`, {
    credentials: 'include'
  });
  if (!res.ok) throw new Error(`complete failed: ${res.status}`);
  return res.json();
}
