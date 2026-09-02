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
