import { css } from 'lit';

export const formStyles = css`
  .field-label {
    display: block;
    font-size: var(--font-size-xs);
    font-weight: var(--font-weight-semi);
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--text-muted);
    margin-bottom: var(--space-3);
  }

  .provider-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: var(--space-3);
    margin-bottom: var(--space-6);
  }

  .provider-card {
    display: flex; align-items: center; gap: var(--space-3);
    padding: var(--space-4);
    background: var(--bg-elevated);
    border: 1.5px solid var(--border-card);
    border-radius: var(--radius-md);
    cursor: pointer;
    transition: all var(--transition);
    position: relative; overflow: hidden;
  }

  .provider-card:hover {
    border-color: var(--accent);
    background: var(--accent-subtle);
    transform: translateY(-1px);
    box-shadow: var(--shadow-sm);
  }

  .provider-card.selected {
    border-color: var(--accent);
    background: var(--accent-subtle);
    box-shadow: 0 0 0 3px var(--accent-glow);
  }

  .provider-card.selected::after {
    content: "✓";
    position: absolute; top: 6px; right: 8px;
    font-size: var(--font-size-xs);
    color: var(--accent);
    font-weight: var(--font-weight-bold);
  }

  .provider-icon {
    width: 32px; height: 32px; flex-shrink: 0;
    border-radius: var(--radius-sm);
    display: flex; align-items: center; justify-content: center;
    font-size: 16px; font-weight: var(--font-weight-bold);
  }

  .provider-name {
    font-size: var(--font-size-sm);
    font-weight: var(--font-weight-medium);
    color: var(--text-primary);
  }

  .provider-tag {
    font-size: 10px;
    color: var(--text-muted);
    margin-top: 1px;
  }

  .field-input, .field-textarea, .field-select {
    width: 100%;
    box-sizing: border-box;
    padding-top: var(--space-3);
    padding-bottom: var(--space-3);
    padding-left: var(--space-4);
    padding-right: var(--space-4);
    background: var(--bg-elevated);
    border: 1.5px solid var(--border);
    border-radius: var(--radius-md);
    color: var(--text-primary);
    font-family: var(--font-family);
    font-size: var(--font-size-sm);
    transition: border-color var(--transition), box-shadow var(--transition),
                background var(--transition);
    outline: none;
    -webkit-appearance: none;
  }

  .field-input:focus, .field-textarea:focus, .field-select:focus {
    border-color: var(--accent);
    box-shadow: 0 0 0 3px var(--accent-glow);
    background: var(--bg-surface);
  }

  .field-input::placeholder, .field-textarea::placeholder {
    color: var(--text-faint);
  }

  .field-textarea {
    min-height: 100px; resize: vertical;
    line-height: 1.6;
  }

  .api-key-field {
    position: relative;
    width: 100%;
  }

  .field-input.has-trailing-action {
    padding-right: 5.5rem !important;
  }

  .field-action-btn {
    position: absolute;
    top: 50%;
    right: 0.875rem;
    transform: translateY(-50%);
    width: 3rem;
    height: 3rem;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border: 0;
    border-radius: 0.9rem;
    background: color-mix(in srgb, var(--bg-elevated) 92%, transparent);
    color: var(--text-muted);
    z-index: 2;
    cursor: pointer;
    flex-shrink: 0;
    transition: color var(--transition), background var(--transition), box-shadow var(--transition);
  }

  .field-action-btn:hover {
    color: var(--text-primary);
    background: var(--accent-subtle);
  }

  .field-action-btn svg {
    width: 1.1rem;
    height: 1.1rem;
    pointer-events: none;
  }

  .model-select-wrap {
    position: relative; margin-top: var(--space-5);
  }

  .model-select-wrap::after {
    content: "";
    position: absolute; right: 14px; top: 50%;
    transform: translateY(-50%);
    width: 0; height: 0;
    border-left: 4px solid transparent;
    border-right: 4px solid transparent;
    border-top: 5px solid var(--text-muted);
    pointer-events: none;
  }

  .btn-row {
    display: flex; gap: var(--space-3);
    margin-top: var(--space-6);
  }

  .btn-primary {
    flex: 1;
    padding: var(--space-3) var(--space-6);
    background: linear-gradient(135deg,
      var(--accent) 0%, color-mix(in srgb, var(--accent) 70%,
      var(--accent-secondary) 30%) 100%);
    border: none;
    border-radius: var(--radius-md);
    color: #fff;
    font-family: var(--font-family);
    font-size: var(--font-size-sm);
    font-weight: var(--font-weight-semi);
    cursor: pointer;
    transition: all var(--transition);
    position: relative; overflow: hidden;
    box-shadow: 0 4px 12px var(--accent-glow);
  }

  .btn-primary:hover {
    transform: translateY(-1px);
    box-shadow: 0 8px 20px var(--accent-glow);
    filter: brightness(1.1);
  }

  .btn-primary:active {
    transform: translateY(0);
    filter: brightness(0.95);
  }

  .btn-primary:disabled {
    opacity: 0.45; cursor: not-allowed;
    transform: none; filter: none;
    box-shadow: none;
  }

  .btn-primary::after {
    content: "";
    position: absolute; inset: 0;
    background: linear-gradient(105deg,
      transparent 30%, rgba(255,255,255,0.12) 50%, transparent 70%);
    transform: translateX(-100%);
    transition: transform 0.5s ease;
  }

  .btn-primary:hover::after { transform: translateX(100%); }

  .btn-ghost {
    padding: var(--space-3) var(--space-5);
    background: transparent;
    border: 1.5px solid var(--border);
    border-radius: var(--radius-md);
    color: var(--text-muted);
    font-family: var(--font-family);
    font-size: var(--font-size-sm);
    font-weight: var(--font-weight-medium);
    cursor: pointer;
    transition: all var(--transition);
  }

  .btn-ghost:hover {
    border-color: var(--accent);
    color: var(--accent);
    background: var(--accent-subtle);
  }

  .status-msg {
    display: flex; align-items: center; gap: var(--space-2);
    padding: var(--space-3) var(--space-4);
    border-radius: var(--radius-md);
    font-size: var(--font-size-sm);
    font-weight: var(--font-weight-medium);
    margin-top: var(--space-4);
    animation: step-in 0.2s ease both;
  }

  .status-msg.success {
    background: var(--success-bg);
    color: var(--success);
    border: 1px solid rgba(34,197,94,0.2);
  }

  .status-msg.error {
    background: var(--error-bg);
    color: var(--error);
    border: 1px solid rgba(239,68,68,0.2);
  }

  .status-msg.loading {
    background: var(--accent-subtle);
    color: var(--accent);
    border: 1px solid var(--border-focus);
  }

  .spinner {
    width: 14px; height: 14px;
    border: 2px solid rgba(255,255,255,0.3);
    border-top-color: #fff;
    border-radius: 50%;
    animation: spin 0.7s linear infinite;
    flex-shrink: 0;
  }

  @keyframes spin { to { transform: rotate(360deg); } }

  .welcome-features {
    display: flex; flex-direction: column;
    gap: var(--space-3); margin: var(--space-6) 0;
  }

  .feature-row {
    display: flex; align-items: center; gap: var(--space-3);
    padding: var(--space-3) var(--space-4);
    background: var(--bg-elevated);
    border: 1px solid var(--border-card);
    border-radius: var(--radius-md);
    font-size: var(--font-size-sm);
    color: var(--text-primary);
  }

  .feature-dot {
    width: 8px; height: 8px; flex-shrink: 0;
    border-radius: var(--radius-full);
    background: linear-gradient(135deg, var(--accent), var(--accent-secondary));
  }

  .mcp-list {
    display: flex; flex-direction: column;
    gap: var(--space-2); margin-bottom: var(--space-4);
  }

  .mcp-item {
    display: flex; align-items: center; gap: var(--space-3);
    padding: var(--space-3) var(--space-4);
    background: var(--bg-elevated);
    border: 1px solid var(--border-card);
    border-radius: var(--radius-md);
    animation: step-in 0.2s ease both;
  }

  .mcp-item-name {
    flex: 1;
    font-size: var(--font-size-sm);
    font-weight: var(--font-weight-medium);
    color: var(--text-primary);
  }

  .mcp-item-tag {
    font-size: var(--font-size-xs);
    color: var(--text-muted);
    background: var(--bg-base);
    padding: 2px 8px;
    border-radius: var(--radius-full);
    border: 1px solid var(--border);
  }

  .mcp-remove {
    width: 24px; height: 24px;
    border-radius: var(--radius-full);
    border: 1px solid var(--border);
    background: transparent;
    color: var(--text-muted);
    cursor: pointer; font-size: 14px;
    display: flex; align-items: center; justify-content: center;
    transition: all var(--transition);
    flex-shrink: 0;
  }

  .mcp-remove:hover {
    background: var(--error-bg);
    color: var(--error);
    border-color: rgba(239,68,68,0.3);
  }

  .add-mcp-btn {
    width: 100%;
    padding: var(--space-3);
    background: transparent;
    border: 1.5px dashed var(--border);
    border-radius: var(--radius-md);
    color: var(--text-muted);
    font-size: var(--font-size-sm);
    cursor: pointer;
    transition: all var(--transition);
  }

  .add-mcp-btn:hover {
    border-color: var(--accent);
    color: var(--accent);
    background: var(--accent-subtle);
  }

  .complete-icon {
    width: 64px; height: 64px; margin: 0 auto var(--space-4);
    background: var(--success-bg);
    border-radius: var(--radius-full);
    display: flex; align-items: center; justify-content: center;
    font-size: 28px;
    border: 2px solid rgba(34,197,94,0.2);
    animation: pulse-success 1.5s ease infinite alternate;
  }

  @keyframes pulse-success {
    from { box-shadow: 0 0 0 0 var(--success-bg); }
    to   { box-shadow: 0 0 0 12px transparent; }
  }

  @keyframes step-in {
    from { opacity: 0; transform: translateX(16px); }
    to   { opacity: 1; transform: translateX(0); }
  }
`;
