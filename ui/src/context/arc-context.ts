import { createContext } from '@lit/context';
import type { SseStatus } from '../api/sse';

export interface ArcAppContext {
  apiKey: string;
  sessionId: string;
  platformId: string;
  workspaceId?: string;
  sseStatus: SseStatus;
}

export const arcContext = createContext<ArcAppContext>('arc-app-context');
