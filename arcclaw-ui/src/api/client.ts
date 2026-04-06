import { AgentStatusDto, PendingApprovalDto, ApprovalResultDto, ToolDto, SkillDto } from './types';

export class ArcClient {
  constructor(
    private readonly baseUrl: string,
    private readonly apiKey: string
  ) {}

  private headers(): HeadersInit {
    return {
      'X-ArcClaw-API-Key': this.apiKey,
      'Content-Type': 'application/json',
    };
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
      headers: { 'X-ArcClaw-API-Key': this.apiKey },
    });
    if (!r.ok) throw new Error(`POST /hitl/${taskId}/approve failed: ${r.status}`);
    return r.json();
  }

  async rejectHitl(taskId: string, reason: string): Promise<ApprovalResultDto> {
    const r = await fetch(`${this.baseUrl}/hitl/${taskId}/reject`, {
      method: 'POST',
      headers: {
        'X-ArcClaw-API-Key': this.apiKey,
        'Content-Type': 'text/plain',
      },
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
}
