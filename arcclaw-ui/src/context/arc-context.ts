import { createContext } from '@lit/context';

export interface ArcAppContext {
  apiKey: string;
  sessionId: string;
  sseStatus: 'connected' | 'disconnected' | 'error';
}

export const arcContext = createContext<ArcAppContext>('arc-app-context');
