export interface RouteConfig {
  hash: string;
  label: string;
  icon: string;
  component: string;
}

export const ROUTES: RouteConfig[] = [
  { hash: '#/chat', label: 'Chat', icon: '💬', component: 'arc-chat-interface' },
  { hash: '#/mcp', label: 'MCP', icon: '🔌', component: 'arc-mcp-panel' },
  { hash: '#/agents', label: 'Agents', icon: '🤖', component: 'arc-agent-status' },
  { hash: '#/hitl', label: 'Approvals', icon: '✋', component: 'arc-hitl-panel' },
  { hash: '#/skills', label: 'Skills', icon: '🔧', component: 'arc-skills-panel' },
  { hash: '#/scheduler', label: 'Scheduler', icon: '⏰', component: 'arc-scheduler-panel' },
  { hash: '#/audit', label: 'Audit', icon: '📋', component: 'arc-audit-panel' },
  { hash: '#/db', label: 'DB Console', icon: '🗄', component: 'arc-db-console' },
  { hash: '#/arena', label: 'Model Arena', icon: '⚔', component: 'arc-model-arena' },
  { hash: '#/settings', label: 'Settings', icon: '⚙', component: 'arc-settings' }
];
