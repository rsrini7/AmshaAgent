import { LitElement, css, html, nothing } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import {
  RUN_EVIDENCE_DECISIONS,
  RUN_EVIDENCE_RISK_LEVELS,
  RUN_EVIDENCE_VALUE,
  RunDecisionEvidenceDto,
  RunEvidenceDto,
} from '../types/trace';

const SAFE_DECISIONS = new Set<string>(RUN_EVIDENCE_DECISIONS);
const SAFE_RISK_LEVELS = new Set<string>(RUN_EVIDENCE_RISK_LEVELS);

@customElement('arc-run-evidence')
export class ArcRunEvidence extends LitElement {
  @property({ attribute: false }) evidence: RunEvidenceDto | null = null;

  static styles = css`
    :host {
      display: block;
      margin: 0.75rem 1rem;
      color: var(--text-primary, #e5e7eb);
      font: 0.85rem/1.4 system-ui, sans-serif;
    }
    section {
      border: 1px solid var(--border-color, #374151);
      border-radius: 0.6rem;
      padding: 0.75rem 1rem;
      background: var(--surface-secondary, rgba(15, 23, 42, 0.62));
    }
    h3,
    h4 {
      margin: 0 0 0.5rem;
      font-size: 0.92rem;
    }
    dl {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr));
      gap: 0.45rem 1rem;
      margin: 0;
    }
    dt {
      color: var(--text-muted, #9ca3af);
      font-size: 0.75rem;
    }
    dd {
      margin: 0;
      overflow-wrap: anywhere;
    }
    .decision-groups {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
      gap: 0.75rem;
      margin-top: 0.75rem;
    }
    ul {
      margin: 0;
      padding-left: 1.1rem;
    }
    .muted {
      color: var(--text-muted, #9ca3af);
    }
    .truncated {
      margin: 0.65rem 0 0;
      color: #fbbf24;
    }
  `;

  private tokenCount(value: number | null | undefined): string {
    return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? String(value) : 'Unavailable';
  }

  private decisionText(item: RunDecisionEvidenceDto): string {
    const decision = SAFE_DECISIONS.has(item.decision) ? item.decision : 'UNKNOWN';
    const risk = item.riskLevel && SAFE_RISK_LEVELS.has(item.riskLevel) ? item.riskLevel : 'Risk unavailable';
    return `${decision} · ${risk} · ${this.safeTime(item.timestamp)}`;
  }

  private safeTime(value: string): string {
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? 'Time unavailable' : date.toISOString();
  }

  private renderDecisions(
    label: string,
    decisions: RunDecisionEvidenceDto[] | null | undefined,
    complete: boolean,
  ) {
    const entries = Array.isArray(decisions) ? decisions : [];
    return html`
      <div>
        <h4>${label}</h4>
        ${entries.length === 0
          ? html`<p class="muted">${complete ? 'None recorded' : 'Unavailable'}</p>`
          : html`<ul>${entries.map((item) => html`<li>${this.decisionText(item)}</li>`)}</ul>`}
      </div>
    `;
  }

  private costText(evidence: RunEvidenceDto | null): string {
    if (!evidence || evidence.costStatus === RUN_EVIDENCE_VALUE.UNAVAILABLE) return 'Unavailable';
    if (evidence.costStatus === RUN_EVIDENCE_VALUE.UNPRICED) return 'Unpriced';
    if (evidence.costStatus !== RUN_EVIDENCE_VALUE.PRICED) return 'Unavailable';
    const amount = evidence.estimatedCost;
    const currency = evidence.costCurrency;
    if (
      typeof amount !== 'number' ||
      !Number.isFinite(amount) ||
      amount < 0 ||
      typeof currency !== 'string' ||
      !/^[A-Z]{3}$/.test(currency)
    ) {
      return 'Unavailable';
    }
    return `${currency} ${amount}`;
  }

  render() {
    const evidence = this.evidence;
    const outcome =
      evidence?.outcomeAvailability === RUN_EVIDENCE_VALUE.OBSERVED
        ? this.safeOutcome(evidence.outcomeStatus)
        : 'Unavailable';
    const latency =
      evidence?.latencyAvailability === RUN_EVIDENCE_VALUE.MEASURED &&
      typeof evidence.latencyMs === 'number' &&
      evidence.latencyMs >= 0
        ? `${evidence.latencyMs} ms`
        : 'Unavailable';
    const usage = evidence?.usageAvailability ?? RUN_EVIDENCE_VALUE.UNAVAILABLE;

    return html`
      <section role="region" aria-label="Run-level evidence">
        <h3>Run evidence</h3>
        <dl>
          <div><dt>Terminal trace outcome</dt><dd>${outcome}</dd></div>
          <div><dt>Latency</dt><dd>${latency}</dd></div>
          <div><dt>Cost</dt><dd>${this.costText(evidence)}</dd></div>
          <div>
            <dt>Token usage</dt>
            <dd>
              ${usage === RUN_EVIDENCE_VALUE.UNAVAILABLE
                ? 'Unavailable'
                : `${usage === RUN_EVIDENCE_VALUE.PARTIAL ? 'Partial · ' : ''}Prompt ${this.tokenCount(evidence?.promptTokens)} · Completion ${this.tokenCount(evidence?.completionTokens)}`}
            </dd>
          </div>
        </dl>
        <div class="decision-groups">
          ${this.renderDecisions(
            'Guard decisions',
            evidence?.guardDecisions,
            Boolean(evidence && evidence.observedEventCount > 0 && !evidence.eventsTruncated),
          )}
          ${this.renderDecisions(
            'Approval decisions',
            evidence?.approvalDecisions,
            Boolean(evidence && evidence.observedEventCount > 0 && !evidence.eventsTruncated),
          )}
        </div>
        ${evidence?.eventsTruncated
          ? html`<p class="truncated">Trace evidence is incomplete or reached its event limit.</p>`
          : evidence?.decisionEvidenceTruncated
            ? html`<p class="truncated">Decision evidence is truncated at its display limit.</p>`
            : nothing}
      </section>
    `;
  }

  private safeOutcome(value: string): string {
    return value === RUN_EVIDENCE_VALUE.END || value === RUN_EVIDENCE_VALUE.UNKNOWN ? value : 'Unknown';
  }
}
