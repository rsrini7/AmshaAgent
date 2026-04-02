import { AgentStatusDto, PendingApprovalDto, ApprovalResultDto, ToolDto, SkillDto } from './types';

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
}

export async function getOnboardingComplete(baseUrl: string): Promise<import('./types').OnboardingCompleteDto> {
  const res = await fetch(`${baseUrl}/onboarding/complete`, {
    credentials: 'include'
  });
  if (!res.ok) throw new Error(`complete failed: ${res.status}`);
  return res.json();
}

export async function getSettings(apiKey: string) {
  const res = await fetch('/settings', {
    headers: { 'X-ARCCLAW-API-KEY': apiKey }
  });
  if (!res.ok) throw new Error(`settings ${res.status}`);
  return res.json();
}

export async function patchProvider(apiKey: string, body: object) {
  const res = await fetch('/settings/provider', {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
      'X-ARCCLAW-API-KEY': apiKey
    },
    body: JSON.stringify(body)
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.error || `PATCH failed: ${res.status}`);
  }
  return res.json();
}

export async function patchPrompt(apiKey: string, prompt: string) {
  const res = await fetch('/settings/prompt', {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
      'X-ARCCLAW-API-KEY': apiKey
    },
    body: JSON.stringify({ systemPrompt: prompt })
  });
  if (!res.ok) throw new Error(`prompt update failed`);
  return res.json();
}

export async function resetOnboarding(apiKey: string) {
  const res = await fetch('/settings/reset', {
    method: 'POST',
    headers: { 'X-ARCCLAW-API-KEY': apiKey }
  });
  if (!res.ok) throw new Error(`reset failed`);
  return res.json();
}
