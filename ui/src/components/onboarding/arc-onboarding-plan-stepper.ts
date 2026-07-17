import { LitElement, css, html } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { formStyles } from '../arc-onboarding-styles';

@customElement('arc-onboarding-plan-stepper')
export class ArcOnboardingPlanStepper extends LitElement {
  static styles = [
    formStyles,
    css`
      :host {
        display: block;
        margin-bottom: var(--space-6);
      }

      .stepper-container {
        display: flex;
        align-items: center;
        justify-content: space-between;
        position: relative;
        padding: 0 var(--space-4);
      }

      .step-item {
        display: flex;
        flex-direction: column;
        align-items: center;
        position: relative;
        z-index: 2;
        flex: 1;
      }

      .step-item:not(:last-child)::after {
        content: '';
        position: absolute;
        top: 14px;
        left: 50%;
        width: 100%;
        height: 2px;
        background: var(--border);
        z-index: -1;
        transition: background var(--transition-slow);
      }

      .step-item.done:not(:last-child)::after {
        background: linear-gradient(90deg, var(--accent-secondary) 0%, var(--accent) 100%);
      }

      .step-dot {
        width: 28px;
        height: 28px;
        border-radius: var(--radius-full);
        border: 2px solid var(--border);
        background: var(--bg-elevated);
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: var(--font-size-xs);
        font-weight: var(--font-weight-semi);
        color: var(--text-muted);
        transition: all var(--transition);
      }

      .step-item.active .step-dot {
        border-color: var(--accent);
        background: var(--accent);
        color: #fff;
        box-shadow: 0 0 0 4px var(--accent-glow);
      }

      .step-item.done .step-dot {
        border-color: var(--accent-secondary);
        background: var(--accent-secondary);
        color: #fff;
      }

      .step-label {
        font-size: 10px;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        color: var(--text-muted);
        margin-top: var(--space-2);
        font-weight: var(--font-weight-medium);
        text-align: center;
        max-width: 80px;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }

      .step-item.active .step-label {
        color: var(--text-primary);
        font-weight: var(--font-weight-bold);
      }

      @media (max-width: 640px) {
        .step-label {
          display: none;
        }
      }
    `
  ];

  @property({ type: Array }) planStages: string[] = [];
  @property({ type: String }) currentStepId = 'welcome';

  render() {
    const currentIndex = this.planStages.indexOf(this.currentStepId);
    
    // To keep the UI clean when there are 14 steps, we only render 5 steps:
    // welcome, connections, concern-routing (routes), MCP, review/complete.
    // Or we can render the core milestones. Let's filter milestones to keep stepper compact.
    const milestones = [
      { id: 'welcome', label: 'Welcome' },
      { id: 'connections', label: 'Connections' },
      { id: 'routes', label: 'Routing' },
      { id: 'mcp', label: 'MCP' },
      { id: 'review', label: 'Review' }
    ];

    return html`
      <div class="stepper-container">
        ${milestones.map((m, idx) => {
          const stageIdxInPlan = this.planStages.indexOf(m.id);
          const currentIdxInPlan = this.planStages.indexOf(this.currentStepId);
          
          let isActive = false;
          let isDone = false;

          if (this.currentStepId === m.id) {
            isActive = true;
          } else if (currentIdxInPlan > stageIdxInPlan && stageIdxInPlan !== -1) {
            isDone = true;
          } else if (idx > 0 && idx < milestones.length - 1) {
            // Check if current step falls between milestones
            const prevMilestoneIdx = this.planStages.indexOf(milestones[idx - 1].id);
            const nextMilestoneIdx = this.planStages.indexOf(m.id);
            if (currentIdxInPlan > prevMilestoneIdx && currentIdxInPlan < nextMilestoneIdx) {
              isActive = true;
            }
          }

          return html`
            <div class="step-item ${isActive ? 'active' : ''} ${isDone ? 'done' : ''}">
              <div class="step-dot">
                ${isDone ? '✓' : idx + 1}
              </div>
              <div class="step-label">${m.label}</div>
            </div>
          `;
        })}
      </div>
    `;
  }
}
