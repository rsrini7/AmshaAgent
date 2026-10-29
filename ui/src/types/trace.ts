/**
 * TypeScript mirror of the Java execution-trace contract
 * (`com.amsha.core.agent.trace.AgentTraceEvent`).
 *
 * These types are the client view of an already-redacted, server-side bounded payload. Nothing
 * here may request raw prompts, completions, secrets, authorization headers, tool parameters,
 * audit keys or Java source — the server never publishes them.
 *
 * TypeScript types require no GraalVM reflection registration (spec §2.4).
 */

/** Discriminator emitted by the server, mirroring `AgentTraceEvent.EVENT_TYPE`. */
export const TRACE_EVENT_TYPE = 'AGENT_TRACE' as const;

/** Contract version, mirroring `AgentTraceEvent.CONTRACT_VERSION`. */
export const TRACE_CONTRACT_VERSION = 1;

/** Lifecycle stages, mirroring the lowercase `snake_case` wire values of `TraceStage`. */
export type TraceStage =
  | 'turn_start'
  | 'preflight'
  | 'route'
  | 'memory_decision'
  | 'guard_check'
  | 'hitl_requested'
  | 'hitl_resolved'
  | 'llm_reasoning'
  | 'tool_start'
  | 'tool_end'
  | 'tool_error'
  | 'quality_check'
  | 'retry'
  | 'hitl_timeout'
  | 'loop_stuck'
  | 'consolidation'
  | 'turn_end';

/** The ten governed harness stations, mirroring the wire values of `StationCode`. */
export type StationCode =
  | 'station_01_inbound'
  | 'station_02_preflight'
  | 'station_03_routing'
  | 'station_04_memory_decision'
  | 'station_05_tool_guard'
  | 'station_06_reasoning'
  | 'station_07_tool_execution'
  | 'station_08_quality'
  | 'station_09_consolidation'
  | 'station_10_outbound';

/** Known `TraceStage` wire values, used to validate untrusted DTO strings. */
const TRACE_STAGES: readonly TraceStage[] = [
  'turn_start',
  'preflight',
  'route',
  'memory_decision',
  'guard_check',
  'hitl_requested',
  'hitl_resolved',
  'llm_reasoning',
  'tool_start',
  'tool_end',
  'tool_error',
  'quality_check',
  'retry',
  'hitl_timeout',
  'loop_stuck',
  'consolidation',
  'turn_end',
];

/** Known `StationCode` wire values, used to validate untrusted DTO strings. */
const STATION_CODES: readonly StationCode[] = [
  'station_01_inbound',
  'station_02_preflight',
  'station_03_routing',
  'station_04_memory_decision',
  'station_05_tool_guard',
  'station_06_reasoning',
  'station_07_tool_execution',
  'station_08_quality',
  'station_09_consolidation',
  'station_10_outbound',
];

/** Validates an untrusted stage string against the closed `TraceStage` union. */
function asTraceStage(value: string): TraceStage {
  return (TRACE_STAGES as readonly string[]).includes(value) ? (value as TraceStage) : 'turn_start';
}

/** Validates an untrusted station string against the closed `StationCode` union. */
function asStationCode(value: string | null | undefined): StationCode | null {
  if (!value) return null;
  return (STATION_CODES as readonly string[]).includes(value) ? (value as StationCode) : null;
}

/** Numeric telemetry only — never content. Mirrors `AgentTraceEvent.TraceMetrics`. */
export interface TraceMetrics {
  promptTokens?: number | null;
  completionTokens?: number | null;
  durationMs?: number | null;
  iteration?: number | null;
  attempt?: number | null;
}

/** A single redacted execution-trace event. */
export interface AgentTraceEvent {
  eventId: string;
  runId: string;
  sessionId: string;
  turnNumber: number;
  sequence: number;
  stage: TraceStage;
  station: StationCode | null;
  status: string | null;
  riskLevel: string | null;
  detail: string | null;
  metrics: TraceMetrics | null;
  timestamp: string;
  paradigm?: string | null;
  /** Read-only server constant: always {@link TRACE_EVENT_TYPE}. */
  eventType: typeof TRACE_EVENT_TYPE;
  /** Read-only server constant: the contract version this payload was emitted with. */
  version: number;
}

/**
 * Narrows an unknown SSE payload to a trace event.
 *
 * Guarding on the server-owned `eventType` discriminator keeps legacy `HitlEvent`,
 * `AgentStatusEvent` and `HeartbeatEvent` payloads flowing through their existing handlers.
 */
export function isAgentTraceEvent(value: unknown): value is AgentTraceEvent {
  if (value === null || typeof value !== 'object') return false;
  const candidate = value as Partial<AgentTraceEvent>;
  return (
    candidate.eventType === TRACE_EVENT_TYPE &&
    typeof candidate.eventId === 'string' &&
    typeof candidate.runId === 'string' &&
    typeof candidate.stage === 'string'
  );
}

/**
 * Returns the de-duplication key of a payload, or `undefined` for legacy events that carry no
 * `eventId` and therefore must never be de-duplicated.
 */
export function traceEventId(value: unknown): string | undefined {
  return isAgentTraceEvent(value) ? value.eventId : undefined;
}

/** Summary record for an execution run across its trace events. */
export interface ExecutionRunSummaryDto {
  runId: string;
  sessionId: string;
  turnNumber: number;
  paradigm: string | null;
  eventCount: number;
  maxSequence: number;
  terminalStatus: string | null;
  startedAt: string;
  completedAt: string | null;
}

/** Shared wire values for the bounded run-evidence view. */
export const RUN_EVIDENCE_VALUE = {
  END: 'END',
  OBSERVED: 'OBSERVED',
  MEASURED: 'MEASURED',
  PARTIAL: 'PARTIAL',
  PRICED: 'PRICED',
  UNPRICED: 'UNPRICED',
  UNAVAILABLE: 'UNAVAILABLE',
  UNKNOWN: 'UNKNOWN',
} as const;

export const RUN_EVIDENCE_DECISIONS = [
  'ALLOW',
  'HITL_REQUIRED',
  'DENIED',
  'APPROVED',
  'AWAITING_APPROVAL',
  'RESUMED',
  'UNKNOWN',
] as const;

export const RUN_EVIDENCE_RISK_LEVELS = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const;

/** Payload-free decision evidence returned by the run-level operator endpoint. */
export interface RunDecisionEvidenceDto {
  decision: (typeof RUN_EVIDENCE_DECISIONS)[number];
  riskLevel: (typeof RUN_EVIDENCE_RISK_LEVELS)[number] | null;
  timestamp: string;
}

/** Bounded run-level evidence; absent measurements carry an explicit availability state. */
export interface RunEvidenceDto {
  runId: string;
  outcomeStatus:
    | typeof RUN_EVIDENCE_VALUE.END
    | typeof RUN_EVIDENCE_VALUE.UNAVAILABLE
    | typeof RUN_EVIDENCE_VALUE.UNKNOWN;
  outcomeAvailability: typeof RUN_EVIDENCE_VALUE.OBSERVED | typeof RUN_EVIDENCE_VALUE.UNAVAILABLE;
  latencyMs: number | null;
  latencyAvailability: typeof RUN_EVIDENCE_VALUE.MEASURED | typeof RUN_EVIDENCE_VALUE.UNAVAILABLE;
  observedEventCount: number;
  eventsTruncated: boolean;
  guardDecisions: RunDecisionEvidenceDto[];
  approvalDecisions: RunDecisionEvidenceDto[];
  decisionEvidenceTruncated: boolean;
  promptTokens: number | null;
  completionTokens: number | null;
  usageAvailability:
    | typeof RUN_EVIDENCE_VALUE.OBSERVED
    | typeof RUN_EVIDENCE_VALUE.PARTIAL
    | typeof RUN_EVIDENCE_VALUE.UNAVAILABLE;
  costStatus:
    | typeof RUN_EVIDENCE_VALUE.PRICED
    | typeof RUN_EVIDENCE_VALUE.UNPRICED
    | typeof RUN_EVIDENCE_VALUE.UNAVAILABLE;
  estimatedCost: number | null;
  costCurrency: string | null;
  costReason: string;
}

/** Persisted execution trace record returned by the replay REST API. */
export interface ExecutionTraceDto {
  eventId: string;
  runId: string;
  sessionId: string;
  turnNumber: number;
  sequence: number;
  stage: string;
  station: string | null;
  status: string | null;
  riskLevel: string | null;
  detail: string | null;
  promptTokens: number | null;
  completionTokens: number | null;
  durationMs: number | null;
  iteration: number | null;
  attempt: number | null;
  timestamp: string;
  paradigm: string | null;
  version: number;
  createdAt: string;
}

/** Maps a persisted ExecutionTraceDto to an AgentTraceEvent for canvas consumption. */
export function toAgentTraceEvent(dto: ExecutionTraceDto): AgentTraceEvent {
  return {
    eventId: dto.eventId,
    runId: dto.runId,
    sessionId: dto.sessionId,
    turnNumber: dto.turnNumber,
    sequence: dto.sequence,
    stage: asTraceStage(dto.stage),
    station: asStationCode(dto.station),
    status: dto.status,
    riskLevel: dto.riskLevel,
    detail: dto.detail,
    metrics:
      dto.promptTokens != null || dto.completionTokens != null || dto.durationMs != null
        ? {
            promptTokens: dto.promptTokens,
            completionTokens: dto.completionTokens,
            durationMs: dto.durationMs,
            iteration: dto.iteration,
            attempt: dto.attempt,
          }
        : null,
    timestamp: dto.timestamp,
    paradigm: dto.paradigm,
    eventType: TRACE_EVENT_TYPE,
    version: dto.version || TRACE_CONTRACT_VERSION,
  };
}
