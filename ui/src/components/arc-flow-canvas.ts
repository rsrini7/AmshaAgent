import { LitElement, css, html, nothing, PropertyValues } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { AgentTraceEvent, ExecutionRunSummaryDto, RunEvidenceDto } from '../types/trace';
import './arc-harness-conveyor';
import './arc-topology-graph';
import './arc-overflow-ledger';
import './arc-run-evidence';
import { computeAnomalyCounts } from './arc-overflow-ledger';

@customElement('arc-flow-canvas')
export class ArcFlowCanvas extends LitElement {
  @property({ type: Array }) events: AgentTraceEvent[] = [];
  @property({ type: String }) runId = '';
  @property({ type: String }) paradigm = '';
  @property({ type: Boolean }) isReplayMode = false;
  @property({ type: Array }) replayRuns: ExecutionRunSummaryDto[] = [];
  @property({ attribute: false }) runEvidence: RunEvidenceDto | null = null;

  @state() private _viewMode: 'conveyor' | 'react' | 'multi_agent' = 'conveyor';
  @state() private _ledgerOpen = false;
  @state() private _runsOpen = false;
  @state() private _scrubIndex = -1;
  @state() private _isPlaying = false;
  @state() private _playbackSpeed: 1 | 2 | 5 = 1;

  private _playbackTimer: number | null = null;

  static styles = css`
    :host {
      display: flex;
      flex-direction: column;
      height: 100%;
      background: var(--bg-base, #0a0b0f);
      color: var(--text-primary, #ffffff);
      overflow-y: auto;
      scrollbar-width: thin;
      scrollbar-color: var(--border, #242938) transparent;
    }

    :host::-webkit-scrollbar {
      width: 6px;
      height: 6px;
    }

    :host::-webkit-scrollbar-track {
      background: transparent;
    }

    :host::-webkit-scrollbar-thumb {
      background: var(--border, #242938);
      border-radius: 999px;
    }

    :host::-webkit-scrollbar-thumb:hover {
      background: var(--text-faint, #4a5568);
    }

    .canvas-toolbar {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 12px 24px;
      border-bottom: 1px solid var(--border, #242938);
      background: color-mix(in srgb, var(--bg-elevated, #1a202c) 70%, transparent);
      flex-shrink: 0;
      gap: 16px;
      flex-wrap: wrap;
    }

    .toolbar-left {
      display: flex;
      align-items: center;
      gap: 10px;
      flex-wrap: wrap;
    }

    .run-badge {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      font-size: 11px;
      padding: 3px 8px;
      border-radius: 6px;
      background: var(--bg-surface, #131722);
      border: 1px solid var(--border, #242938);
      color: var(--text-muted, #8b949e);
    }

    .run-id-value {
      font-family: monospace;
      color: var(--text-primary, #ffffff);
    }

    .toolbar-combo-badge {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: 10px;
      font-weight: 700;
      letter-spacing: 0.04em;
      padding: 3px 8px;
      border-radius: 999px;
      text-transform: uppercase;
    }

    .toolbar-badge-retry {
      background: rgba(245, 158, 11, 0.2);
      color: #f59e0b;
      border: 1px solid rgba(245, 158, 11, 0.4);
    }

    .toolbar-badge-error {
      background: rgba(239, 68, 68, 0.2);
      color: #ef4444;
      border: 1px solid rgba(239, 68, 68, 0.4);
    }

    .toolbar-badge-stuck {
      background: rgba(239, 68, 68, 0.3);
      color: #ff6b6b;
      border: 1px solid #ef4444;
    }

    .toolbar-right {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .ledger-toggle-btn {
      background: var(--bg-surface, #131722);
      border: 1px solid var(--border, #242938);
      color: var(--text-muted, #8b949e);
      padding: 4px 10px;
      font-size: 11px;
      font-weight: 600;
      border-radius: 6px;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 6px;
      transition: background-color 0.2s, color 0.2s, border-color 0.2s;
    }

    .ledger-toggle-btn.active {
      background: color-mix(in srgb, var(--accent, #6c63ff) 20%, transparent);
      color: var(--text-primary, #ffffff);
      border-color: var(--accent, #6c63ff);
    }

    .ledger-toggle-btn:hover {
      background: var(--bg-elevated, #1a202c);
      color: var(--text-primary, #ffffff);
    }

    .view-toggle-group {
      display: inline-flex;
      background: var(--bg-surface, #131722);
      border: 1px solid var(--border, #242938);
      border-radius: 8px;
      padding: 2px;
      gap: 2px;
    }

    .view-btn {
      background: transparent;
      border: none;
      color: var(--text-muted, #8b949e);
      padding: 4px 10px;
      font-size: 11px;
      font-weight: 600;
      border-radius: 6px;
      cursor: pointer;
      transition: background-color 0.2s, color 0.2s;
    }

    .view-btn.active {
      background: var(--accent, #6c63ff);
      color: #ffffff;
    }

    .view-btn:hover:not(.active):not(:disabled) {
      background: color-mix(in srgb, var(--bg-elevated, #1a202c) 80%, transparent);
      color: var(--text-primary, #ffffff);
    }

    .view-btn:disabled {
      opacity: 0.35;
      cursor: not-allowed;
    }

    .canvas-body {
      flex: 1;
      min-height: 0;
      display: flex;
      flex-direction: column;
    }

    .ledger-section {
      padding: 16px 24px;
      border-bottom: 1px solid var(--border, #242938);
      background: color-mix(in srgb, var(--bg-surface, #131722) 40%, transparent);
    }

    .replay-banner {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 8px 24px;
      background: linear-gradient(90deg, #1e3a8a 0%, #2563eb 100%);
      color: #ffffff;
      font-size: 12px;
      font-weight: 600;
      gap: 12px;
      flex-shrink: 0;
    }

    .replay-banner-left {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .replay-tag {
      background: rgba(255, 255, 255, 0.2);
      padding: 2px 6px;
      border-radius: 4px;
      font-size: 10px;
      letter-spacing: 0.05em;
      text-transform: uppercase;
      font-weight: 700;
    }

    .replay-banner-right {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .replay-btn {
      background: rgba(255, 255, 255, 0.15);
      border: 1px solid rgba(255, 255, 255, 0.3);
      color: #ffffff;
      padding: 4px 10px;
      font-size: 11px;
      font-weight: 600;
      border-radius: 6px;
      cursor: pointer;
      transition: background-color 0.2s;
    }

    .replay-btn:hover {
      background: rgba(255, 255, 255, 0.25);
    }

    .replay-exit-btn {
      background: rgba(239, 68, 68, 0.85);
      border: 1px solid rgba(239, 68, 68, 1);
      color: #ffffff;
      padding: 4px 10px;
      font-size: 11px;
      font-weight: 600;
      border-radius: 6px;
      cursor: pointer;
      transition: background-color 0.2s;
    }

    .replay-exit-btn:hover {
      background: #dc2626;
    }

    .scrub-toolbar {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 8px 24px;
      background: color-mix(in srgb, var(--bg-surface, #131722) 90%, transparent);
      border-bottom: 1px solid var(--border, #242938);
      font-size: 12px;
      flex-shrink: 0;
      flex-wrap: wrap;
    }

    .scrub-controls {
      display: inline-flex;
      align-items: center;
      gap: 4px;
    }

    .scrub-btn {
      background: var(--bg-surface, #131722);
      border: 1px solid var(--border, #242938);
      color: var(--text-primary, #ffffff);
      padding: 4px 8px;
      font-size: 11px;
      font-weight: 600;
      border-radius: 6px;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      min-width: 28px;
    }

    .scrub-btn:hover:not(:disabled) {
      background: var(--bg-elevated, #1a202c);
    }

    .scrub-btn:disabled {
      opacity: 0.35;
      cursor: not-allowed;
    }

    .scrub-slider-container {
      flex: 1;
      min-width: 150px;
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .scrub-slider {
      flex: 1;
      accent-color: var(--accent, #6c63ff);
      cursor: pointer;
    }

    .scrub-step-label {
      font-family: monospace;
      font-size: 11px;
      color: var(--text-muted, #8b949e);
      white-space: nowrap;
    }

    .toolbar-badge-gap {
      background: rgba(239, 68, 68, 0.2);
      color: #f87171;
      border: 1px solid rgba(239, 68, 68, 0.4);
    }

    .runs-dropdown {
      position: relative;
      display: inline-block;
    }

    .runs-menu {
      position: absolute;
      top: 100%;
      right: 0;
      margin-top: 6px;
      background: var(--bg-elevated, #1a202c);
      border: 1px solid var(--border, #242938);
      border-radius: 8px;
      box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
      z-index: 100;
      min-width: 260px;
      max-height: 280px;
      overflow-y: auto;
      padding: 4px 0;
    }

    .runs-menu-item {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 8px 12px;
      cursor: pointer;
      font-size: 11px;
      border-bottom: 1px solid color-mix(in srgb, var(--border, #242938) 50%, transparent);
      transition: background-color 0.15s;
    }

    .runs-menu-item:hover {
      background: color-mix(in srgb, var(--accent, #6c63ff) 15%, transparent);
    }

    .runs-menu-item:last-child {
      border-bottom: none;
    }
  `;

  public setViewMode(mode: 'conveyor' | 'react' | 'multi_agent') {
    const norm = (this.paradigm || '').trim().toUpperCase();
    if (mode === 'react' && norm !== 'REACT') {
      this._viewMode = 'conveyor';
      return;
    }
    if (mode === 'multi_agent' && norm !== 'MULTI_AGENT' && norm !== 'GROUP_CONSULTATION') {
      this._viewMode = 'conveyor';
      return;
    }
    this._viewMode = mode;
  }

  get viewMode(): 'conveyor' | 'react' | 'multi_agent' {
    return this._viewMode;
  }

  get visibleEvents(): AgentTraceEvent[] {
    if (!this.isReplayMode || this._scrubIndex < 0 || this._scrubIndex >= this.events.length) {
      return this.events;
    }
    return this.events.slice(0, this._scrubIndex + 1);
  }

  private _detectSequenceGaps(evts: AgentTraceEvent[]): number {
    let gaps = 0;
    for (let i = 1; i < evts.length; i++) {
      if (evts[i].sequence - evts[i - 1].sequence > 1) {
        gaps++;
      }
    }
    return gaps;
  }

  private _togglePlay() {
    if (this._isPlaying) {
      this._pause();
    } else {
      this._play();
    }
  }

  private _play() {
    if (this.events.length === 0) return;
    if (this._scrubIndex === -1 || this._scrubIndex >= this.events.length - 1) {
      this._scrubIndex = 0;
    }
    this._isPlaying = true;
    this._scheduleNextStep();
  }

  private _pause() {
    this._isPlaying = false;
    if (this._playbackTimer !== null) {
      window.clearTimeout(this._playbackTimer);
      this._playbackTimer = null;
    }
  }

  private _scheduleNextStep() {
    if (!this._isPlaying) return;
    const delay = Math.max(100, Math.round(1000 / this._playbackSpeed));
    this._playbackTimer = window.setTimeout(() => {
      if (!this._isPlaying) return;
      const current = this._scrubIndex === -1 ? this.events.length - 1 : this._scrubIndex;
      if (current < this.events.length - 1) {
        this._scrubIndex = current + 1;
        this._scheduleNextStep();
      } else {
        this._pause();
      }
    }, delay);
  }

  private _stepForward() {
    this._pause();
    const current = this._scrubIndex === -1 ? this.events.length - 1 : this._scrubIndex;
    if (current < this.events.length - 1) {
      this._scrubIndex = current + 1;
    }
  }

  private _stepBackward() {
    this._pause();
    const current = this._scrubIndex === -1 ? this.events.length - 1 : this._scrubIndex;
    if (current > 0) {
      this._scrubIndex = current - 1;
    }
  }

  private _onScrubInput(e: Event) {
    this._pause();
    const target = e.target as HTMLInputElement;
    const v = Number.parseInt(target.value, 10);
    if (Number.isNaN(v)) return;
    const max = Math.max(0, this.events.length - 1);
    this._scrubIndex = Math.min(Math.max(v, 0), max);
  }

  private _cycleSpeed() {
    if (this._playbackSpeed === 1) this._playbackSpeed = 2;
    else if (this._playbackSpeed === 2) this._playbackSpeed = 5;
    else this._playbackSpeed = 1;
    if (this._isPlaying) {
      this._pause();
      this._play();
    }
  }

  private _onExitReplay() {
    this._pause();
    this._scrubIndex = -1;
    this.dispatchEvent(new CustomEvent('exit-replay', { bubbles: true, composed: true }));
    // Surface the run list so the shell can refresh session runs after replay ends.
    this.dispatchEvent(new CustomEvent('refresh-runs', { bubbles: true, composed: true }));
  }

  private _onSelectRun(runId: string) {
    this._pause();
    this._scrubIndex = -1;
    this._runsOpen = false;
    this.dispatchEvent(new CustomEvent('start-replay', { detail: { runId }, bubbles: true, composed: true }));
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this._pause();
  }

  updated(changed: PropertyValues) {
    if (changed.has('isReplayMode') && this.isReplayMode) {
      // Replay starts showing the full trace; align the scrubber thumb to the end
      // (previously it rendered at events.length - 1 while the index was -1, so the
      // displayed slice and the thumb disagreed).
      this._scrubIndex = Math.max(0, this.events.length - 1);
    }
  }

  render() {
    const visible = this.visibleEvents;
    const activeRun = this.runId || (this.events.length > 0 ? this.events[0].runId : 'idle');
    const norm = (this.paradigm || '').trim().toUpperCase();
    const reactAvailable = norm === 'REACT';
    const multiAgentAvailable = norm === 'MULTI_AGENT' || norm === 'GROUP_CONSULTATION';
    const anomalyCounts = computeAnomalyCounts(visible);
    const sequenceGaps = this._detectSequenceGaps(visible);

    const currentScrubIndex =
      this._scrubIndex === -1
        ? Math.max(0, this.events.length - 1)
        : Math.min(this._scrubIndex, Math.max(0, this.events.length - 1));
    const currentSeq =
      this.events.length > 0 && currentScrubIndex < this.events.length
        ? this.events[currentScrubIndex].sequence
        : 0;

    return html`
      ${this.isReplayMode
        ? html`
            <div class="replay-banner" role="status" aria-label="Replay Mode Banner">
              <div class="replay-banner-left">
                <span class="replay-tag">Replay Mode</span>
                <span>Run: <strong>${activeRun}</strong></span>
                ${this.replayRuns.length > 1
                  ? html`
                      <select
                        class="replay-btn"
                        style="background: rgba(0,0,0,0.3); color: #fff;"
                        @change="${(e: Event) =>
                          this._onSelectRun((e.target as HTMLSelectElement).value)}"
                      >
                        ${this.replayRuns.map(
                          (r) =>
                            html`<option value="${r.runId}" ?selected="${r.runId === this.runId}">
                              Turn ${r.turnNumber} (${r.eventCount} evts - ${r.terminalStatus || 'OK'})
                            </option>`
                        )}
                      </select>
                    `
                  : nothing}
              </div>
              <div class="replay-banner-right">
                <button
                  class="replay-exit-btn"
                  @click="${this._onExitReplay}"
                  aria-label="Exit Replay Mode"
                >
                  Exit Replay
                </button>
              </div>
            </div>

            <div class="scrub-toolbar" role="toolbar" aria-label="Replay Scrubbing Controls">
              <div class="scrub-controls">
                <button
                  class="scrub-btn"
                  @click="${this._stepBackward}"
                  ?disabled="${currentScrubIndex <= 0}"
                  title="Step Backward"
                  aria-label="Step Backward"
                >
                  ⏮
                </button>
                <button
                  class="scrub-btn"
                  @click="${this._togglePlay}"
                  title="${this._isPlaying ? 'Pause Playback' : 'Start Playback'}"
                  aria-label="${this._isPlaying ? 'Pause' : 'Play'}"
                >
                  ${this._isPlaying ? '⏸' : '▶'}
                </button>
                <button
                  class="scrub-btn"
                  @click="${this._stepForward}"
                  ?disabled="${currentScrubIndex >= this.events.length - 1}"
                  title="Step Forward"
                  aria-label="Step Forward"
                >
                  ⏭
                </button>
                <button
                  class="scrub-btn"
                  @click="${this._cycleSpeed}"
                  title="Cycle Playback Speed"
                  aria-label="Playback Speed"
                >
                  ${this._playbackSpeed}x
                </button>
              </div>

              <div class="scrub-slider-container">
                <input
                  type="range"
                  class="scrub-slider"
                  min="0"
                  max="${Math.max(0, this.events.length - 1)}"
                  .value="${String(currentScrubIndex)}"
                  @input="${this._onScrubInput}"
                  aria-label="Timeline Scrubber"
                />
                <span class="scrub-step-label">
                  ${this.events.length > 0 ? currentScrubIndex + 1 : 0} / ${this.events.length} (seq #${currentSeq})
                </span>
              </div>
            </div>
          `
        : nothing}

      ${this.isReplayMode
        ? html`<arc-run-evidence .evidence=${this.runEvidence}></arc-run-evidence>`
        : nothing}

      <div class="canvas-toolbar" role="toolbar" aria-label="Visual Cockpit Controls">
        <div class="toolbar-left">
          <div class="run-badge">
            <span>Execution Run:</span>
            <span class="run-id-value">${activeRun ? activeRun.slice(0, 10) : 'none'}</span>
          </div>
          <div class="run-badge">
            <span>Observed Events:</span>
            <span class="run-id-value">${visible.length}${this.isReplayMode ? ` / ${this.events.length}` : ''}</span>
          </div>

          ${sequenceGaps > 0
            ? html`<span class="toolbar-combo-badge toolbar-badge-gap" title="Missing sequence numbers detected">⚠ NOT_OBSERVED (${sequenceGaps} gaps)</span>`
            : nothing}

          ${anomalyCounts.retryCount > 0
            ? html`<span class="toolbar-combo-badge toolbar-badge-retry">⚡ ${anomalyCounts.retryCount}x RETRY</span>`
            : nothing}

          ${anomalyCounts.errorCount > 0
            ? html`<span class="toolbar-combo-badge toolbar-badge-error">⚠ ${anomalyCounts.errorCount}x TOOL ERROR</span>`
            : nothing}

          ${anomalyCounts.stuckCount > 0
            ? html`<span class="toolbar-combo-badge toolbar-badge-stuck">🔥 LOOP STUCK</span>`
            : nothing}
        </div>

        <div class="toolbar-right">
          ${!this.isReplayMode && this.replayRuns.length > 0
            ? html`
                <div class="runs-dropdown">
                  <button
                    class="ledger-toggle-btn ${this._runsOpen ? 'active' : ''}"
                    @click="${() => (this._runsOpen = !this._runsOpen)}"
                    aria-label="Select previous run for replay"
                  >
                    <span>⏮ Runs</span>
                    <span style="font-family: monospace;">(${this.replayRuns.length})</span>
                  </button>
                  ${this._runsOpen
                    ? html`
                        <div class="runs-menu" role="menu">
                          ${this.replayRuns.map(
                            (r) => html`
                              <div
                                class="runs-menu-item"
                                role="menuitem"
                                @click="${() => this._onSelectRun(r.runId)}"
                              >
                                <div>
                                  <strong>Turn ${r.turnNumber}</strong>
                                  <span style="color: var(--text-muted); font-family: monospace;">
                                    (${r.runId.slice(0, 12)}...)
                                  </span>
                                </div>
                                <div style="display: flex; gap: 6px; align-items: center;">
                                  <span style="font-family: monospace;">${r.eventCount} evts</span>
                                  <span style="color: ${r.terminalStatus === 'OK' ? '#10b981' : '#f59e0b'};">
                                    ${r.terminalStatus || 'OK'}
                                  </span>
                                </div>
                              </div>
                            `
                          )}
                        </div>
                      `
                    : nothing}
                </div>
              `
            : nothing}

          <button
            class="ledger-toggle-btn ${this._ledgerOpen ? 'active' : ''}"
            @click="${() => (this._ledgerOpen = !this._ledgerOpen)}"
            aria-label="Toggle overflow ledger"
            aria-expanded="${this._ledgerOpen}"
          >
            <span>Overflow Ledger</span>
            <span style="font-family: monospace;">(${anomalyCounts.totalAnomalies})</span>
          </button>

          <div class="view-toggle-group" role="radiogroup" aria-label="Topology View Mode">
            <button
              class="view-btn ${this._viewMode === 'conveyor' ? 'active' : ''}"
              role="radio"
              aria-checked="${this._viewMode === 'conveyor'}"
              @click="${() => this.setViewMode('conveyor')}"
            >
              Harness Conveyor
            </button>
            <button
              class="view-btn ${this._viewMode === 'react' ? 'active' : ''}"
              role="radio"
              aria-checked="${this._viewMode === 'react'}"
              ?disabled="${!reactAvailable}"
              title="${reactAvailable ? 'View ReAct execution loop' : 'Requires backend REACT paradigm metadata'}"
              @click="${() => reactAvailable && this.setViewMode('react')}"
            >
              ReAct Loop
            </button>
            <button
              class="view-btn ${this._viewMode === 'multi_agent' ? 'active' : ''}"
              role="radio"
              aria-checked="${this._viewMode === 'multi_agent'}"
              ?disabled="${!multiAgentAvailable}"
              title="${multiAgentAvailable ? 'View Multi-Agent hub-and-spoke graph' : 'Requires backend MULTI_AGENT paradigm metadata'}"
              @click="${() => multiAgentAvailable && this.setViewMode('multi_agent')}"
            >
              Multi-Agent
            </button>
          </div>
        </div>
      </div>

      ${this._ledgerOpen
        ? html`
            <div class="ledger-section">
              <arc-overflow-ledger .events=${visible} ?open=${true}></arc-overflow-ledger>
            </div>
          `
        : nothing}

      <div class="canvas-body" role="main">
        ${this._viewMode === 'conveyor'
          ? html`<arc-harness-conveyor .events=${visible}></arc-harness-conveyor>`
          : html`<arc-topology-graph
              .events=${visible}
              .viewMode=${this._viewMode}
              .paradigm=${this.paradigm}
            ></arc-topology-graph>`}
      </div>
    `;
  }
}
