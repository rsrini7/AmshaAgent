export interface PendingApprovalDto {
  taskId: string;
  sessionId?: string;
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
  | {
      type: 'message';
      text: string;
      attachment?: {
        mimeType: 'audio/mpeg' | 'audio/wav' | 'audio/ogg' | 'audio/webm';
        fileName: string;
        data: string;
      };
    }
  | { type: 'AUTH'; token: string; };

export interface WsConnectedEvent {
  type: 'connected';
  sessionId: string;
  platformId: string;
}

export interface WsAssistantAudio {
  mimeType: string;
  fileName: string;
  data: string;
  url?: string;
}

export interface WsAssistantMessage {
  type: 'message';
  role: 'assistant';
  text: string;
  audio?: WsAssistantAudio;
  sessionId?: string;
  approval?: {
    tool: string;
    params?: Record<string, unknown> | string;
  };
  timestamp?: number;
}

export interface WsApprovalRequiredFrame {
  type: 'approval_required';
  role: 'assistant';
  text: string;
  sessionId: string;
  toolName: string;
  instructions?: string;
  params?: Record<string, unknown> | string;
}

export interface WsErrorFrame {
  type: 'error';
  text: string;
}

export type WsInboundFrame =
  | WsConnectedEvent
  | WsAssistantMessage
  | WsApprovalRequiredFrame
  | WsErrorFrame;

export function parseAgentPayload(payload: string): { status: string; task: string | null } {
  const sep = payload.indexOf(' : ');
  if (sep === -1) return { status: payload.trim(), task: null };
  return { status: payload.slice(0, sep).trim(), task: payload.slice(sep + 3).trim() };
}

// Onboarding DTOs
export interface OnboardingStatusDto {
  step: number;
  stepName: string;
  provider: string | null;
  model: string | null;
  credentialsOk: boolean;
  skipOnboarding: boolean;
  vaultPriority: boolean;
  effectiveProvider?: string | null;
  effectiveModel?: string | null;
  effectiveBaseUrl?: string | null;
  effectiveSource?: 'ENVIRONMENT' | 'VAULT' | 'DEFAULT' | 'NONE' | string;
  credentialState?: 'NOT_REQUIRED' | 'CONFIGURED' | 'MISSING' | string;
  credentialSource?: 'ENVIRONMENT' | 'VAULT' | 'NONE' | string;
}
export interface SettingsDto {
  provider: string;
  model: string;
  systemPrompt: string;
  hasApiKey: boolean;
  appVersion: string;
  skipOnboarding: boolean;
  vaultPriority: boolean;
  effectiveBaseUrl?: string | null;
  effectiveSource?: 'ENVIRONMENT' | 'VAULT' | 'DEFAULT' | 'NONE' | string;
  credentialState?: 'NOT_REQUIRED' | 'CONFIGURED' | 'MISSING' | string;
  credentialSource?: 'ENVIRONMENT' | 'VAULT' | 'NONE' | string;
  voice?: string;
  speechOutputMode?: string;
  availableVoices?: string[];
}

export interface VoiceSettingsUpdateRequest {
  voice?: string;
  speechOutputMode?: string;
}

export interface ProviderCatalogModel {
  id: string;
  label: string;
  apiTier?: string | null;
}

export interface ProviderDescriptor {
  id: string;
  displayName: string;
  i18nKey: string;
  authScheme: string;
  defaultBaseUrl: string | null;
  requiresApiKey: boolean;
  embeddingCapable: boolean;
  modelCatalog: ProviderCatalogModel[];
  attributes: Record<string, string>;
}

export interface OnboardingCompleteDto {
  complete: boolean;
  chatUrl: string;
  provider: string;
  model: string;
  pluginSteps: Array<{stepId:string; title:string; isConfigured:boolean}>;
}

export type EgressMode = 'ALLOW_ALL' | 'ALLOW_LIST' | 'DENY_ALL';

export type StructuredOutputMode = 'NATIVE' | 'PROMPT_DIRECTIVE' | 'LENIENT';

export interface NetworkProfilePolicyDto {
  egressMode?: EgressMode;
  allowedHosts?: string[];
  requireProxy?: boolean;
  proxyUrl?: string;
  proxyCredentialVaultRef?: string;
  noProxyHosts?: string[];
  connectTimeoutMs?: number;
  readTimeoutMs?: number;
  tlsTrustMode?: 'SYSTEM' | 'CUSTOM_CA';
  customCaVaultRef?: string;
}

export interface ModelProfileDraft {
  id: string;
  connectionId: string;
  modelId: string;
  structuredOutputMode?: StructuredOutputMode;
  capabilityJson?: string;
  safeLimitsJson?: string;
  enabled?: boolean;
}

export interface ConnectionDraft {
  id?: string;
  displayName?: string;
  provider: string;
  apiKeyVaultRef?: string;
  apiKey?: string;
  baseUrl?: string;
  targetUrl?: string; // targetUrl is required specifically for generic-openai
  modelId?: string;
  contextWindow?: number;
  structuredOutputMode?: StructuredOutputMode;
  capabilities?: string[];
  headroomEnabled?: boolean;
  timeoutMs?: number;
  retries?: number;
  networkProfileJson?: string;
  profiles?: ModelProfileDraft[];
}

export interface ConcernRoutes {
  agentLoop: number[];
  compaction: number[];
  classifier: number[];
  embedding: number[];
}

export interface RouteAssignment {
  primary: number;
  failover: number[];
}

export interface McpServerDraft {
  name: string;
  transport: string;
  command?: string;
  args?: string;
  url?: string;
  category?: string;
  headers?: Record<string, string>;
  headerVaultRefs?: Record<string, string>;
  envVars?: Record<string, string>;
  healthCheckIntervalMs?: number;
}

export interface IntegrationsConfig {
  playwrightEnabled: boolean;
  playwrightHeaded: boolean;
  braveApiKeyVaultRef?: string;
  serperApiKeyVaultRef?: string;
  githubTokenVaultRef?: string;
  skillsjarsApprovalRequired: boolean;
  skillsMaxIterations?: number;
}

export interface AdvancedSettings {
  temperature?: number;
  maxOutputTokens?: number;
  timeoutMs?: number;
  maxRetries?: number;
  compactPrompt?: boolean;
  circuitBreaker?: boolean;
  delegationDepth?: number;
  budgetLimit?: number;
  compactionStructuredOutputMode?: string; // Controls global compaction output format (e.g. from .env)
}

export interface MemoryConfig {
  tieredEnabled: boolean;
  coreMaxBlocks?: number;
  recallRetention?: number;
  archivalEnabled?: boolean;
  selfEvolveEnabled: boolean;
  embedDimensions?: number;
  ragVecPath?: string;
}

export interface SecurityConfig {
  rateLimitRequestsPerMin?: number;
  rateLimitMutationsPerMin?: number;
  sessionIdleMinutes?: number;
  sandboxStrategy?: string;
  sandboxMemoryLimit?: number;
  sandboxCpuLimit?: number;
  archivePassVaultRef?: string;
  hostExecSkills?: string[];
}

export interface ChannelsConfig {
  telegramEnabled: boolean;
  telegramBotTokenVaultRef?: string;
  telegramAllowedIds?: string[];
  webEnabled: boolean;
  webRequireAuth: boolean;
  timezone?: string;
  cronStartupMs?: number;
  phoenixEnabled: boolean;
  phoenixEndpoint?: string;
}

export interface IdentityConfig {
  oidcClientId?: string;
  oidcClientSecretVaultRef?: string;
  oidcAuthUrl?: string;
  oidcTokenUrl?: string;
  oidcUserinfoUrl?: string;
  oidcjwksUrl?: string;
  oidcRedirectUri?: string;
}

export interface O1DraftPayload {
  connections: ConnectionDraft[];
  profiles?: ModelProfileDraft[];
  concernRoutes: ConcernRoutes;
  personaRoutes?: Record<string, RouteAssignment>;
  groupRoutes?: Record<string, RouteAssignment>;
  mcpServers?: McpServerDraft[];
  /** Backend MCP extension payload; generated from mcpServers before persistence. */
  mcp?: { servers: Array<{ name: string; endpoint: string; category: string; headers?: Record<string, string> }> };
  integrations?: IntegrationsConfig;
  advanced?: AdvancedSettings;
  memory?: MemoryConfig;
  security?: SecurityConfig;
  channels?: ChannelsConfig;
  identity?: IdentityConfig;
}

export interface OnboardingFieldDescriptorDto {
  name: string;
  type: string;
  required: boolean;
  options: string[];
  title: string;
  description: string;
}

export interface OnboardingStepDescriptorDto {
  id: string;
  version: string;
  order: number;
  optional: boolean;
  title: string;
  description: string;
  fields: OnboardingFieldDescriptorDto[];
}

export interface ConnectionValidationResponse {
  tier: string;
  status: string;
  approvalId?: string | null;
  summary?: string | null;
  valid: boolean;
  safeErrors?: string | null;
}

export interface ApprovalResolutionResponse {
  approvalId: string;
  status: string;
  verified: boolean;
  message: string;
}
