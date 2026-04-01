export interface AgentStatusDto {
  agentName: string;
  role: string;
  status: 'active' | 'idle' | 'waiting_approval' | 'error';
  currentTask: string | null;
  autonomyLevel: 'readonly' | 'background' | 'supervised' | 'full';
  tokenCount: number;
}

export interface PendingApprovalDto {
  taskId: string;
  toolName: string;
  params: Record<string, unknown>;
  requestedAt: string;
  agentName: string;
}

export interface ToolDto {
  name: string;
  description: string;
  inputSchema: string | null; // serialized JSON of JsonObjectSchema
}

export interface SkillDto {
    id: string;
    name: string;
    description: string;
    source: string;
    triggerPatterns: string[];
}

export interface ApprovalResultDto {
  status: 'approved' | 'rejected';
  taskId: string;
  reason?: string;
}

// WebSocket Message Shapes
export interface WsOutboundMessage {
  type: 'message';
  text: string;
}

export interface WsConnectedEvent {
  type: 'connected';
  sessionId: string;
  platformId: string;
}

export interface WsAssistantMessage {
  type: 'message';
  role: 'assistant';
  text: string;
}

export type WsInboundFrame = WsConnectedEvent | WsAssistantMessage;

// SSE Event Shapes
export interface AgentEvent {
  eventType: 'status_change' | 'heartbeat';
  agentName: string;
  payload: string;
}

export function parseAgentPayload(payload: string): { status: string; task: string | null } {
  const sep = payload.indexOf(' : ');
  if (sep === -1) return { status: payload.trim(), task: null };
  return { status: payload.slice(0, sep).trim(), task: payload.slice(sep + 3).trim() };
}

// Onboarding DTOs
export interface OnboardingStatusDto {
  step: number;
  name: string;
  provider: string | null;
  model: string | null;
  credentialsValidated: boolean;
}

export interface McpServerRequest {
  name: string;
  transport: 'sse' | 'stdio';
  url?: string;
  command?: string;
  args?: string;
  env?: string;
}

export interface OnboardingCompleteDto {
  done: boolean;
  redirectTo: string;
  provider: string;
  model: string;
  pluginSteps: { stepId: string; title: string; configured: boolean }[];
}
