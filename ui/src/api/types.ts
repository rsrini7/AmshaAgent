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
  inputSchema: string | null;
}

export interface ApprovalResultDto {
  status: 'approved' | 'rejected';
  taskId: string;
  reason?: string;
}

// WebSocket Message Shapes
export type WsOutboundMessage = 
  | { type: 'message'; text: string; }
  | { type: 'AUTH'; token: string; };

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

export interface OnboardingCompleteDto {
  complete: boolean;
  chatUrl: string;
  provider: string;
  model: string;
  serviceKey: string;
  pluginSteps: Array<{stepId:string; title:string; isConfigured:boolean}>;
}
