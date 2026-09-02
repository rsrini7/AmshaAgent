import { LitElement, css, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { AgentTraceEvent } from '../types/trace';
import './arc-node-card';
import { normalizeEventStatus } from './arc-harness-conveyor';

export const SUPPORTED_PARADIGMS = ['REACT', 'MULTI_AGENT'] as const;
export type SupportedParadigm = typeof SUPPORTED_PARADIGMS[number];

/**
 * Resolves the effective topology view.
 *
 * Backend paradigm metadata is the primary authority.
 * If paradigm metadata is absent, unknown, or inconsistent (e.g. unconfirmed paradigms
 * like PLAN_EXECUTE or REFLEXION), it MUST fall back to the conveyor view rather than
 * guessing or fabricating a topology.
 */
export function resolveTopologyView(
  paradigm: string | null | undefined,
  requestedMode?: 'conveyor' | 'react' | 'multi_agent'
): 'conveyor' | 'react' | 'multi_agent' {
  // If backend paradigm metadata is absent or unsupported, ALWAYS fall back to conveyor.
  // ReAct or Multi-Agent cannot be forcibly enabled when backend metadata is absent or unsupported.
  if (!isParadigmSupported(paradigm)) {
    return 'conveyor';
  }

  // If a supported mode was requested:
  if (requestedMode === 'conveyor') {
    return 'conveyor';
  }
  if (requestedMode === 'react') {
    return 'react';
  }
  if (requestedMode === 'multi_agent') {
    return 'multi_agent';
  }

  // Default to backend paradigm
  const norm = (paradigm || '').trim().toUpperCase();
  if (norm === 'REACT') {
    return 'react';
  }
  if (norm === 'MULTI_AGENT' || norm === 'GROUP_CONSULTATION') {
    return 'multi_agent';
  }

  return 'conveyor';
}

export function isParadigmSupported(paradigm: string | null | undefined): boolean {
  if (!paradigm) return false;
  const norm = paradigm.trim().toUpperCase();
  return norm === 'REACT' || norm === 'MULTI_AGENT' || norm === 'GROUP_CONSULTATION';
}

@customElement('arc-topology-graph')
export class ArcTopologyGraph extends LitElement {
  @property({ type: Array }) events: AgentTraceEvent[] = [];
  @property({ type: String }) viewMode: 'conveyor' | 'react' | 'multi_agent' = 'conveyor';
  @property({ type: String }) paradigm = '';
  @property({ type: Boolean }) isInternal = false;

  static styles = css`
    :host {
      display: block;
      width: 100%;
      height: 100%;
      padding: 24px;
      box-sizing: border-box;
      overflow-y: auto;
    }

    .topology-container {
      max-width: 1000px;
      margin: 0 auto;
      display: flex;
      flex-direction: column;
      gap: 24px;
    }

    .topology-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      border-bottom: 1px solid var(--border, #242938);
      padding-bottom: 12px;
    }

    .topology-title {
      font-size: 16px;
      font-weight: 700;
      color: var(--text-primary, #ffffff);
    }

    .paradigm-badge {
      font-size: 10px;
      font-weight: 700;
      letter-spacing: 0.05em;
      text-transform: uppercase;
      padding: 3px 8px;
      border-radius: 999px;
      background: color-mix(in srgb, var(--accent, #6c63ff) 20%, transparent);
      color: var(--accent, #6c63ff);
      border: 1px solid color-mix(in srgb, var(--accent, #6c63ff) 40%, transparent);
    }

    .graph-layout {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
      gap: 16px;
      align-items: start;
    }

    .cycle-indicator {
      grid-column: 1 / -1;
      text-align: center;
      font-size: 12px;
      color: var(--text-muted, #8b949e);
      padding: 8px;
      border-radius: 8px;
      background: color-mix(in srgb, var(--bg-surface, #131722) 60%, transparent);
      border: 1px dashed var(--border, #242938);
    }
  `;

  render() {
    const effectiveMode = resolveTopologyView(this.paradigm, this.viewMode);

    if (effectiveMode === 'conveyor') {
      return html`<arc-harness-conveyor .events=${this.events}></arc-harness-conveyor>`;
    }

    if (effectiveMode === 'react') {
      return this._renderReActLoop();
    }

    return this._renderMultiAgent();
  }

  private _renderReActLoop() {
    // Derive node states strictly from actual observed events only — no fabrication
    const reasoningEvent = this.events.find((e) => e.stage === 'llm_reasoning');
    const guardEvent = [...this.events].reverse().find(
      (e) =>
        e.stage === 'guard_check' ||
        e.stage === 'hitl_requested' ||
        e.stage === 'hitl_resolved' ||
        e.stage === 'hitl_timeout'
    );
    const toolEvent = [...this.events].reverse().find(
      (e) =>
        e.stage === 'tool_start' ||
        e.stage === 'tool_end' ||
        e.stage === 'tool_error' ||
        e.stage === 'retry'
    );

    const hasReasoning = Boolean(reasoningEvent);
    const hasGuard = Boolean(guardEvent);
    const hasTool = Boolean(toolEvent);

    const guardStatus = guardEvent
      ? normalizeEventStatus(guardEvent.stage, guardEvent.status)
      : 'IDLE';

    const toolStatus = toolEvent
      ? normalizeEventStatus(toolEvent.stage, toolEvent.status)
      : 'IDLE';

    return html`
      <div class="topology-container" role="region" aria-label="ReAct Topology View">
        <div class="topology-header">
          <div class="topology-title">ReAct Paradigm: Thought → Action → Observation</div>
          <span class="paradigm-badge">Backend Paradigm: REACT</span>
        </div>

        <div class="cycle-indicator">
          Cyclic Execution Loop: Thought (LLM) ➔ Action Guard & Exec ➔ Observation Feedback
        </div>

        <div class="graph-layout">
          <arc-node-card
            label="Thought (Reasoning)"
            station="station_06_reasoning"
            status="${hasReasoning ? 'COMPLETED' : 'IDLE'}"
            .symbol=${hasReasoning ? 'EngineStepHandler' : null}
            .isInternal=${this.isInternal}
          ></arc-node-card>

          <arc-node-card
            label="Action (Guard & Invocation)"
            station="station_05_tool_guard"
            status="${guardStatus}"
            riskLevel="${guardEvent?.riskLevel || null}"
            .symbol=${hasGuard ? 'ToolInvocationGuard' : null}
            .isInternal=${this.isInternal}
          ></arc-node-card>

          <arc-node-card
            label="Observation (Tool Result)"
            station="station_07_tool_execution"
            status="${toolStatus}"
            .symbol=${hasTool ? 'SequentialToolExecutor' : null}
            .isInternal=${this.isInternal}
          ></arc-node-card>
        </div>
      </div>
    `;
  }

  private _renderMultiAgent() {
    const routeEvent = this.events.find((e) => e.stage === 'route' || e.stage === 'turn_start');
    const delegateEvent = this.events.find(
      (e) =>
        (e.stage === 'tool_start' || e.stage === 'tool_end') &&
        (e.detail?.toLowerCase().includes('delegate') ||
          e.detail?.toLowerCase().includes('consult') ||
          e.detail?.toLowerCase().includes('group'))
    );
    const qualityEvent = this.events.find((e) => e.stage === 'quality_check');

    const hasRouting = Boolean(routeEvent);
    const hasDelegate = Boolean(delegateEvent);
    const hasQuality = Boolean(qualityEvent);

    return html`
      <div class="topology-container" role="region" aria-label="Multi-Agent Topology View">
        <div class="topology-header">
          <div class="topology-title">Multi-Agent Consultation: Hub-and-Spoke</div>
          <span class="paradigm-badge">Backend Paradigm: MULTI_AGENT</span>
        </div>

        <div class="cycle-indicator">
          Consultation Flow: Orchestrator / Triage ➔ Specialized Delegates ➔ Aggregator / Quality
        </div>

        <div class="graph-layout">
          <arc-node-card
            label="Orchestrator / Triage"
            station="station_03_routing"
            status="${hasRouting ? 'COMPLETED' : 'IDLE'}"
            .symbol=${hasRouting ? 'AgentLoop' : null}
            .isInternal=${this.isInternal}
          ></arc-node-card>

          <arc-node-card
            label="Specialist Delegates"
            station="station_07_tool_execution"
            status="${hasDelegate ? 'COMPLETED' : 'IDLE'}"
            .symbol=${hasDelegate ? 'SequentialToolExecutor' : null}
            .isInternal=${this.isInternal}
          ></arc-node-card>

          <arc-node-card
            label="Aggregator / Quality"
            station="station_08_quality"
            status="${qualityEvent ? normalizeEventStatus(qualityEvent.stage, qualityEvent.status) : 'IDLE'}"
            .symbol=${hasQuality ? 'FinishActionHandler' : null}
            .isInternal=${this.isInternal}
          ></arc-node-card>
        </div>
      </div>
    `;
  }
}
