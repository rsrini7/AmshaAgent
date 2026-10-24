import { describe, expect, it } from 'vitest';
import { parseAgentPayload } from './types';

describe('parseAgentPayload', () => {
  it('splits a status from its task and trims both fields', () => {
    expect(parseAgentPayload('  running  :  research the release  ')).toEqual({
      status: 'running',
      task: 'research the release',
    });
  });

  it('returns a trimmed status without a task when the separator is absent', () => {
    expect(parseAgentPayload('  idle  ')).toEqual({ status: 'idle', task: null });
  });
});
