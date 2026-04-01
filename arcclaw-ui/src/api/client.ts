import { AgentStatusDto, PendingApprovalDto, ApprovalResultDto, ToolDto, SkillDto } from './types';

export class ArcClient {
  constructor(
    private readonly baseUrl: string,
    private readonly apiKey?: string
  ) {}

  private headers(): HeadersInit {
    const h: Record<string, string> = {
      'Content-Type': 'application/json',
    };
    if (this.apiKey) {
      h['X-ArcClaw-API-Key'] = this.apiKey;
    }
    return h;
  }

  async getAgents(): Promise<AgentStatusDto[]> {
    const r = await fetch(`${this.baseUrl}/agents`, { headers: this.headers() });
    if (!r.ok) throw new Error(`GET /agents failed: ${r.status}`);
    return r.json();
  }

  async getHitl(): Promise<PendingApprovalDto[]> {
    const r = await fetch(`${this.baseUrl}/hitl`, { headers: this.headers() });
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
    const customHeaders = this.headers();
    (customHeaders as Record<string, string>)['Content-Type'] = 'text/plain';

    const r = await fetch(`${this.baseUrl}/hitl/${taskId}/reject`, {
      method: 'POST',
      headers: customHeaders,
      body: reason || 'Rejected by user',
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

    // Onboarding — uses the instance baseUrl
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
      if (!res.ok) throw new Error(`step${step} failed: ${res.status}`);
      return res.json();
    }

    async onboardingComplete(): Promise<import('./types').OnboardingCompleteDto> {
      const res = await fetch(`${this.baseUrl}/onboarding/complete`, { credentials: 'include' });
      if (!res.ok) throw new Error(`complete failed: ${res.status}`);
      return res.json();
    }
}
