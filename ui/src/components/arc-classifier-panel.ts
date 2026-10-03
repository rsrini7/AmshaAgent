import { LitElement, css, html, PropertyValues } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { consume } from '@lit/context';
import { arcContext, ArcAppContext } from '../context/arc-context';
import { ArcApiError, ArcClient, ClassifierSnapshot } from '../api/client';

/**
 * Read-only cockpit view of the three ONNX classifier runtime slots.
 *
 * <p>Scope is deliberately narrow. The only thing this panel reads is
 * `GET /admin/local-ml/onnx/classifiers`, so the only thing it can honestly report is the
 * requested/effective switch state of the three slots. It cannot see the intent router's
 * activation gate, candidate evidence, cost approvals, or rollout ownership, and it must never
 * imply otherwise — hence {@link CLASSIFIER_SCOPE_NOTICE}, which is rendered unconditionally.
 *
 * <p>The notice enumerates every decision-plane namespace it excludes, and that list must be
 * updated whenever a new one appears. `amsha.decision.providers.*` (the decision-provider slots)
 * was added after the panel shipped, and a notice that named only `amsha.decision.shadow.*` would
 * have left a user free to assume provider selection was visible here. Enumerated exclusions are
 * only useful while they stay exhaustive; the panel test pins the namespaces for that reason.
 *
 * <p>It also offers no enable/disable control: the decision plane is Deferred, no implementation
 * registers, and every enable would be rejected with `MISSING_READY_IMPLEMENTATION`.
 */

/** Slot ids the endpoint guarantees, in the order `OnnxClassifierRuntimeControl.snapshots()` emits them. */
export const EXPECTED_CLASSIFIER_IDS = ['intent', 'tool-risk', 'answer-quality'] as const;

const CLASSIFIER_LABELS: Record<string, string> = {
  intent: 'Intent classifier',
  'tool-risk': 'Tool-risk classifier',
  'answer-quality': 'Answer-quality classifier',
};

export const CLASSIFIER_SCOPE_NOTICE =
  'This view reports the three ONNX classifier runtime slots from GET /admin/local-ml/onnx/classifiers. It does NOT report the amsha.decision.shadow.* settings, the amsha.decision.providers.* decision-provider slots (which provider, model, wire profile or credential reference is selected), decision-engine state, intent-router activation gate, candidate evidence, cost approvals, or rollout ownership — none of those are part of this response and none are inferred here. The decision provider is environment-only and has no cockpit surface.';

/** A 403 is only a role problem when the server says so; a CSRF rejection is also a 403. */
const ROLE_FORBIDDEN_PATTERN = /\b(internal|role\s+required|operator\s+role)\b/i;

export type ClassifierStateTone = 'effective' | 'requested' | 'off';

export interface ClassifierSlotView {
  id: string;
  label: string;
  tone: ClassifierStateTone;
  headline: string;
  detail: string;
  startupEnabled: boolean;
  requestedEnabled: boolean;
  implementationAvailable: boolean;
  effectiveEnabled: boolean;
  acceptingRequests: boolean;
  draining: boolean;
  inFlight: number;
  revision: number;
}

export type ClassifierView =
  | { kind: 'LOADING' }
  | { kind: 'READY'; slots: ClassifierSlotView[] }
  | { kind: 'FORBIDDEN' }
  | { kind: 'UNAUTHENTICATED' }
  | { kind: 'MALFORMED'; detail: string }
  | { kind: 'ERROR'; detail: string };

/**
 * Derives the display state of one slot.
 *
 * The tone is driven by `effectiveEnabled`, not by `requestedEnabled`: a switch that is on while no
 * implementation is registered serves nothing, and showing it as "on" would overstate the system.
 */
export function describeClassifierSlot(snapshot: ClassifierSnapshot): ClassifierSlotView {
  const id = snapshot.classifier;
  const label = CLASSIFIER_LABELS[id] ?? id;

  let tone: ClassifierStateTone;
  let headline: string;
  let detail: string;

  if (snapshot.effectiveEnabled) {
    tone = 'effective';
    headline = 'EFFECTIVE';
    detail = 'The switch is on and a ready implementation is registered, so this slot is serving.';
  } else if (snapshot.requestedEnabled) {
    tone = 'requested';
    headline = 'REQUESTED — NOT IN EFFECT';
    detail = snapshot.implementationAvailable
      ? 'The switch is on and an implementation is registered, yet the slot does not report as effective. Treat this as an inconsistency, not a normal state.'
      : 'The switch is on but no ready implementation has registered, so nothing is serving. Re-enabling is rejected with MISSING_READY_IMPLEMENTATION until one registers.';
  } else {
    tone = 'off';
    headline = 'OFF';
    if (snapshot.implementationAvailable) {
      detail = 'Off at runtime. A ready implementation is registered but is not being asked to serve.';
    } else if (snapshot.startupEnabled) {
      detail = 'Configured on at startup, but the runtime switch is currently off.';
    } else {
      detail = 'Off at startup and off at runtime.';
    }
  }

  if (snapshot.draining) {
    detail += ` ${snapshot.inFlight} in-flight request(s) still draining.`;
  }

  return {
    id,
    label,
    tone,
    headline,
    detail,
    startupEnabled: snapshot.startupEnabled,
    requestedEnabled: snapshot.requestedEnabled,
    implementationAvailable: snapshot.implementationAvailable,
    effectiveEnabled: snapshot.effectiveEnabled,
    acceptingRequests: snapshot.acceptingRequests,
    draining: snapshot.draining,
    inFlight: snapshot.inFlight,
    revision: snapshot.revision,
  };
}

function isClassifierSnapshot(value: unknown): value is ClassifierSnapshot {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate.classifier === 'string' &&
    typeof candidate.startupEnabled === 'boolean' &&
    typeof candidate.requestedEnabled === 'boolean' &&
    typeof candidate.implementationAvailable === 'boolean' &&
    typeof candidate.effectiveEnabled === 'boolean' &&
    typeof candidate.acceptingRequests === 'boolean' &&
    typeof candidate.draining === 'boolean' &&
    Number.isSafeInteger(candidate.inFlight) &&
    (candidate.inFlight as number) >= 0 &&
    Number.isSafeInteger(candidate.revision) &&
    (candidate.revision as number) >= 0
  );
}

/**
 * Validates a 200 payload.
 *
 * The backend maps over `Classifier.values()`, so a successful response always carries exactly the
 * three known slots. An empty or partial list therefore means the contract moved, and reporting it
 * as MALFORMED is more honest than rendering a quiet, empty panel.
 */
export function resolveClassifierStatus(response: unknown): ClassifierView {
  if (typeof response !== 'object' || response === null) {
    return { kind: 'MALFORMED', detail: 'The endpoint returned no JSON object.' };
  }
  const raw = (response as { classifiers?: unknown }).classifiers;
  if (!Array.isArray(raw)) {
    return { kind: 'MALFORMED', detail: 'The response carries no classifiers array.' };
  }
  if (!raw.every(isClassifierSnapshot)) {
    return { kind: 'MALFORMED', detail: 'One or more classifier entries have missing, invalid, or inconsistent fields.' };
  }
  const present = new Set(raw.map((slot) => slot.classifier));
  const missing = EXPECTED_CLASSIFIER_IDS.filter((id) => !present.has(id));
  if (missing.length > 0) {
    return {
      kind: 'MALFORMED',
      detail: `Expected all three slots, but the response omitted ${missing.join(', ')}.`,
    };
  }
  if (raw.length !== EXPECTED_CLASSIFIER_IDS.length || present.size !== raw.length) {
    return {
      kind: 'MALFORMED',
      detail: 'Expected exactly one entry for each known classifier slot and no additional entries.',
    };
  }
  return { kind: 'READY', slots: raw.map(describeClassifierSlot) };
}

/** Maps a failed request to a view that names the actual cause. */
export function resolveClassifierFailure(error: unknown): ClassifierView {
  if (error instanceof ArcApiError) {
    if (error.status === 401) return { kind: 'UNAUTHENTICATED' };
    if (error.status === 403) {
      return ROLE_FORBIDDEN_PATTERN.test(error.message)
        ? { kind: 'FORBIDDEN' }
        : { kind: 'ERROR', detail: error.message || 'Access forbidden (403).' };
    }
    return {
      kind: 'ERROR',
      detail: error.message || `Classifier status request failed with status ${error.status}.`,
    };
  }
  const detail =
    error instanceof Error && error.message ? error.message : 'Classifier status request failed.';
  return { kind: 'ERROR', detail };
}

@customElement('arc-classifier-panel')
export class ArcClassifierPanel extends LitElement {
  @consume({ context: arcContext, subscribe: true })
  @state()
  private _appContext!: ArcAppContext;

  @state() private _view: ClassifierView = { kind: 'LOADING' };
  @state() private _loading = false;
  private _reloadAfterLoad = false;

  static styles = css`
    :host { display: block; padding: var(--space-8); overflow-y: auto; }
    .header { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--space-4); margin-bottom: var(--space-6); }
    h1 { margin: 0; font-size: var(--font-size-xl); }
    h2 { margin: 0; font-size: var(--font-size-lg); }
    p { color: var(--text-muted); margin: var(--space-1) 0 0; }
    code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 0.85em; }
    button { border: 1px solid var(--border); background: var(--bg-elevated); color: var(--text-primary); border-radius: var(--radius-sm); padding: 0.55rem 0.8rem; cursor: pointer; transition: var(--transition); }
    button:hover:not(:disabled) { border-color: var(--accent); background: var(--accent-glow); }
    button:disabled { opacity: 0.5; cursor: wait; }
    .card { background: var(--bg-surface); border: 1px solid var(--border); border-radius: var(--radius-md); padding: var(--space-5); margin-bottom: var(--space-4); }
    .scope { border-left: 3px solid var(--accent); }
    .scope strong { color: var(--text-primary); }
    .scope p { font-size: var(--font-size-sm); }
    .notice-blocked { border-left: 3px solid var(--warning); }
    .notice-blocked strong { color: var(--text-primary); }
    .hint { font-size: var(--font-size-xs); color: var(--text-faint); }
    .slot-head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--space-4); }
    .slot-id { margin-top: var(--space-1); }
    .slot-detail { font-size: var(--font-size-sm); }
    .badge { font-size: var(--font-size-xs); font-weight: var(--font-weight-semi); letter-spacing: 0.04em; padding: 3px 8px; border-radius: var(--radius-full); white-space: nowrap; border: 1px solid transparent; }
    .badge-effective { color: var(--accent-secondary); background: var(--accent-secondary-glow); border-color: var(--accent-secondary); }
    .badge-requested { color: var(--warning); background: var(--warning-bg); border-color: var(--warning); }
    .badge-off { color: var(--text-muted); background: var(--bg-elevated); border-color: var(--border); }
    .facts { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: var(--space-2) var(--space-4); margin: var(--space-4) 0 0; padding-top: var(--space-3); border-top: 1px solid var(--border); }
    .facts > div { display: flex; justify-content: space-between; gap: var(--space-2); }
    dt { color: var(--text-muted); font-size: var(--font-size-xs); }
    dd { margin: 0; font-size: var(--font-size-xs); color: var(--text-primary); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
    @media (max-width: 700px) { :host { padding: var(--space-4); } .slot-head { flex-direction: column; } }
  `;

  connectedCallback() {
    super.connectedCallback();
    void this._load();
  }

  protected override updated(changedProperties: PropertyValues): void {
    if (changedProperties.has('_appContext')) {
      const previous = changedProperties.get('_appContext') as ArcAppContext | undefined;
      if (this._appContext?.apiKey !== previous?.apiKey) {
        if (this._loading) this._reloadAfterLoad = true;
        else void this._load();
      }
    }
  }

  private get _client(): ArcClient {
    return new ArcClient('/api/v1', this._appContext?.apiKey || '');
  }

  async _load(): Promise<void> {
    if (this._loading) return;
    this._loading = true;
    this._view = { kind: 'LOADING' };
    try {
      this._view = resolveClassifierStatus(await this._client.getClassifierRuntimeStatus());
    } catch (error) {
      this._view = resolveClassifierFailure(error);
    } finally {
      this._loading = false;
      if (this._reloadAfterLoad && this.isConnected) {
        this._reloadAfterLoad = false;
        void this._load();
      }
    }
  }

  private _renderSlot(slot: ClassifierSlotView) {
    return html`
      <section class="card slot">
        <div class="slot-head">
          <div>
            <h2>${slot.label}</h2>
            <p class="slot-id"><code>${slot.id}</code></p>
          </div>
          <span class="badge badge-${slot.tone}">${slot.headline}</span>
        </div>
        <p class="slot-detail">${slot.detail}</p>
        <dl class="facts">
          <div><dt>Startup switch</dt><dd>${slot.startupEnabled ? 'on' : 'off'}</dd></div>
          <div><dt>Requested switch</dt><dd>${slot.requestedEnabled ? 'on' : 'off'}</dd></div>
          <div><dt>Implementation registered</dt><dd>${slot.implementationAvailable ? 'yes' : 'no'}</dd></div>
          <div><dt>Effective</dt><dd>${slot.effectiveEnabled ? 'yes' : 'no'}</dd></div>
          <div><dt>Accepting requests</dt><dd>${slot.acceptingRequests ? 'yes' : 'no'}</dd></div>
          <div><dt>In flight</dt><dd>${slot.inFlight}</dd></div>
          <div><dt>Draining</dt><dd>${slot.draining ? 'yes' : 'no'}</dd></div>
          <div><dt>Revision</dt><dd>${slot.revision}</dd></div>
        </dl>
      </section>
    `;
  }

  private _renderBody() {
    const view = this._view;
    switch (view.kind) {
      case 'LOADING':
        return html`<section class="card" role="status" aria-live="polite"><p>Loading classifier status…</p></section>`;
      case 'FORBIDDEN':
        return html`
          <section class="card notice-blocked" role="alert">
            <strong>Internal role required</strong>
            <p>
              This endpoint is restricted to API keys holding the <code>internal</code> role. The
              session is authenticated but not authorised, so no slot state can be shown.
            </p>
            <p class="hint">
              This is a permissions result — not an empty configuration and not an idle plane. Have an
              operator issue a key with the internal role, then refresh.
            </p>
          </section>
        `;
      case 'UNAUTHENTICATED':
        return html`
          <section class="card notice-blocked" role="alert">
            <strong>Authentication expired or missing API key (401)</strong>
            <p>Sign in again, then refresh to read the classifier slots.</p>
          </section>
        `;
      case 'MALFORMED':
        return html`
          <section class="card notice-blocked" role="alert">
            <strong>Unexpected response shape</strong>
            <p>${view.detail}</p>
            <p class="hint">
              A 200 from this endpoint always carries exactly three slots, so treat this as a defect
              rather than as "nothing configured".
            </p>
          </section>
        `;
      case 'ERROR':
        return html`
          <section class="card notice-blocked" role="alert">
            <strong>Could not read classifier status</strong>
            <p>${view.detail}</p>
          </section>
        `;
      case 'READY':
        return html`${view.slots.map((slot) => this._renderSlot(slot))}`;
    }
  }

  render() {
    return html`
      <div class="header">
        <div>
          <h1>Classifier runtime status</h1>
          <p>
            Read-only switch state for the three ONNX classifier runtime slots. It does not expose
            the Jev decision-shadow settings, the decision-provider slots
            (amsha.decision.providers.*), or engine state, and cannot change anything.
          </p>
        </div>
        <button @click=${this._load} ?disabled=${this._loading}>
          ${this._loading ? 'Loading…' : 'Refresh'}
        </button>
      </div>

      <section class="card scope" role="note">
        <strong>Scope and limits</strong>
        <p>${CLASSIFIER_SCOPE_NOTICE}</p>
      </section>

      ${this._renderBody()}
    `;
  }
}
