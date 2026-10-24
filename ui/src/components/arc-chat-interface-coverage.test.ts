import { afterEach, describe, expect, it, vi } from 'vitest';
import type { PendingApprovalDto } from '../api/types';
import { ArcChatInterface } from './arc-chat-interface';

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>();
  get length(): number { return this.values.size; }
  clear(): void { this.values.clear(); }
  getItem(key: string): string | null { return this.values.get(key) ?? null; }
  key(index: number): string | null { return [...this.values.keys()][index] ?? null; }
  removeItem(key: string): void { this.values.delete(key); }
  setItem(key: string, value: string): void { this.values.set(key, value); }
}

function createChat() {
  const windowMock = {
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
    setInterval: vi.fn((_callback: TimerHandler, _timeout?: number) => 7),
    clearInterval: vi.fn(),
    setTimeout: vi.fn(() => 9),
    clearTimeout: vi.fn(),
    location: { protocol: 'http:', host: 'localhost:8080' },
  };
  vi.stubGlobal('sessionStorage', new MemoryStorage());
  vi.stubGlobal('localStorage', new MemoryStorage());
  vi.stubGlobal('window', windowMock);
  const chat = new ArcChatInterface();
  Object.defineProperty(chat, 'updateComplete', { configurable: true, value: Promise.resolve(true) });
  vi.spyOn(chat as any, '_scrollToBottom').mockImplementation(() => undefined);
  return { chat, windowMock };
}

function makeFile(name: string, content: string, type: string): File {
  return Object.assign(new Blob([content], { type }), { name }) as File;
}

describe('ArcChatInterface session, progress, and media behavior', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('accepts the server session and loads only valid historic chat messages', async () => {
    const { chat, windowMock } = createChat();
    const component = chat as any;
    component._appContext = { apiKey: 'owner-key', sessionId: 'context-session', platformId: 'context-platform' };
    component.pendingApprovals = [
      { taskId: 'approval-1', sessionId: 'context-session', toolName: 'search_web' },
      { taskId: 'approval-2', sessionId: 'context-session:delegate:agent-1', toolName: 'read_file' },
      { taskId: 'approval-3', sessionId: 'other-session', toolName: 'search_web' },
      { taskId: 'approval-4', sessionId: 'context-session', toolName: 'channel_tool' },
    ] as PendingApprovalDto[];
    component._messages = [{
      id: 'channel-card', role: 'assistant', text: 'Approval in channel', timestamp: Date.now(),
      isApprovalPrompt: true, targetSessionId: 'context-session', approvalData: { tool: 'channel_tool' },
    }];

    expect(component.currentSessionId()).toBe('context-session');
    expect(component._currentSessionApprovals.map((item: PendingApprovalDto) => item.taskId)).toEqual(['approval-1', 'approval-2']);
    expect(component.acceptServerSession('  ')).toBe('context-session');
    expect(component.acceptServerSession(' session-1 ', ' platform-1 ')).toBe('session-1');
    expect(sessionStorage.getItem('amsha-chat-session')).toBe('session-1');
    expect(windowMock.dispatchEvent).toHaveBeenCalledWith(expect.objectContaining({
      type: 'session-ready', detail: { sessionId: 'session-1', platformId: 'platform-1' },
    }));

    const history = [
      { role: 'user', text: 'Hi', timestamp: 100, actionId: 'action-1' },
      { role: 'assistant', text: 'Approval required for search_web', timestamp: 200 },
      { role: 'assistant', text: 'Session halted by user', timestamp: 300 },
      { role: 'system', text: 'ignored', timestamp: 400 },
    ];
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(history), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    }));
    vi.stubGlobal('fetch', fetchMock);

    const loaded = await component._loadHistory(undefined, true);

    expect(loaded).toHaveLength(3);
    expect(loaded[0]).toMatchObject({ role: 'user', text: 'Hi', historic: true, actionId: 'action-1' });
    expect(loaded[1].isApprovalPrompt).toBe(true);
    expect(component._sessionHalted).toBe(true);
    expect(component._messages).toEqual(loaded);
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/chat/stream/history/session-1', expect.objectContaining({
      credentials: 'include', headers: { 'X-Amsha-API-Key': 'owner-key' },
    }));
  });

  it('ignores stale history responses and retains state on failed or non-OK requests', async () => {
    const { chat } = createChat();
    const component = chat as any;
    component._appContext = { sessionId: '' };
    expect(await component._loadHistory()).toEqual([]);

    component._authoritativeSessionId = 'current-session';
    const originalMessages = [{ id: 'kept', role: 'assistant', text: 'kept', timestamp: 1 }];
    component._messages = originalMessages;
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('forbidden', { status: 403 })));
    expect(await component._loadHistory('current-session')).toEqual([]);
    expect(component._messages).toBe(originalMessages);

    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network unavailable')));
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    expect(await component._loadHistory('current-session')).toEqual([]);
    expect(component._messages).toBe(originalMessages);
  });

  it('tracks provider retry countdown and scopes progress to the active chat or cron session', () => {
    vi.useFakeTimers();
    const { chat, windowMock } = createChat();
    const component = chat as any;
    const startTime = new Date('2026-10-24T00:00:00.000Z');
    vi.setSystemTime(startTime);
    component._appContext = { sessionId: 'session-1' };
    let tick: (() => void) | undefined;
    windowMock.setInterval.mockImplementation((callback: TimerHandler) => {
      tick = callback as () => void;
      return 7;
    });

    component._showRetryCountdown(2500, startTime.toISOString());
    expect(component._progressPhase).toBe('Rate limit hit');
    expect(component._progressDetail).toContain('3 seconds');
    expect(component._isTyping).toBe(true);
    vi.setSystemTime(new Date(startTime.getTime() + 3000));
    tick?.();
    expect(component._progressPhase).toBe('Retrying now');
    expect(windowMock.clearInterval).toHaveBeenCalledWith(7);

    component._onProgress(new CustomEvent('progress', { detail: { sessionId: 'other-session', phase: 'Ignored', detail: 'stale' } }));
    expect(component._progressPhase).toBe('Retrying now');
    component._onProgress(new CustomEvent('progress', { detail: { sessionId: 'session-1', phase: 'Drafting', detail: 'Final answer' } }));
    component._onProgress(new CustomEvent('progress', { detail: { sessionId: 'session-1', phase: 'Drafting', detail: 'Final answer' } }));
    expect(component._progressTrail).toEqual([{ phase: 'Drafting', detail: 'Final answer' }]);
    expect(component._isTyping).toBe(true);

    component._activeBackgroundCronSessionId = 'cron-1';
    component._onProgress(new CustomEvent('progress', { detail: { sessionId: 'cron-1', phase: 'Scheduled task', detail: 'Running' } }));
    expect(component._progressPhase).toBe('Scheduled task');
    component._resetProgress();
    expect(component._progressTrail).toEqual([]);
    expect(component._retryDeadlineMs).toBeNull();
  });

  it('checks backend status without resending and reports terminal, approval, live, and failed states', async () => {
    const { chat } = createChat();
    const component = chat as any;
    component._appContext = { apiKey: 'owner-key', sessionId: 'session-1' };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ status: 'AWAITING_APPROVAL' }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        status: 'IN_PROGRESS', activeGoal: 'Find release', activePhase: 'Web fetch', failReason: '',
      }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ status: 'COMPLETED' }), { status: 200 }))
      .mockRejectedValueOnce(new Error('timeout'));
    vi.stubGlobal('fetch', fetchMock);

    await component._checkStalledTurn();
    expect(component._awaitingApproval).toBe(true);
    expect(component._progressPhase).toBe('Waiting for approval');

    await component._checkStalledTurn();
    expect(component._progressPhase).toBe('Still working');
    expect(component._progressDetail).toContain('Find release');
    expect(component._progressDetail).toContain('Web fetch');

    vi.spyOn(component, '_loadHistory').mockResolvedValue([
      { role: 'assistant', text: 'Found it', isApprovalPrompt: false },
    ]);
    await component._checkStalledTurn();
    expect(component._progressPhase).toBe('');
    expect(component._turnStalled).toBe(false);

    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    await component._checkStalledTurn();
    expect(component._progressPhase).toBe('Status unavailable');
    expect(component._statusChecking).toBe(false);
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it('validates audio uploads and resolves MIME types from content or filename', () => {
    const { chat } = createChat();
    const component = chat as any;
    const wav = makeFile('voice.WAV', 'audio', '');
    const mp3 = makeFile('voice.bin', 'audio', 'audio/mp3; codecs=mp3');
    const unsupported = makeFile('notes.txt', 'data', 'text/plain');
    const empty = makeFile('empty.wav', '', 'audio/wav');

    expect(component._isSupportedAudioFile(wav)).toBe(true);
    expect(component._resolveAttachmentMimeType(wav)).toBe('audio/wav');
    expect(component._isSupportedAudioFile(mp3)).toBe(true);
    expect(component._resolveAttachmentMimeType(mp3)).toBe('audio/mpeg');
    expect(component._isSupportedAudioFile(unsupported)).toBe(false);
    expect(component._isSupportedAudioFile(empty)).toBe(false);

    const input = { files: [unsupported], value: 'notes.txt' };
    component._onAudioFileSelected({ target: input } as unknown as Event);
    expect(component._attachmentError).toContain('Supported audio formats');
    expect(input.value).toBe('');
    component._onAudioFileSelected({ target: { files: [], value: '' } } as unknown as Event);
    expect(component._audioAttachment).toBeNull();
    component._onAudioFileSelected({ target: { files: [wav], value: 'voice.WAV' } } as unknown as Event);
    expect(component._audioAttachment).toBe(wav);
    expect(component._attachmentError).toBe('');
  });

  it('sends text only while connected and handles failed audio encoding without sending', async () => {
    const { chat } = createChat();
    const component = chat as any;
    const send = vi.fn();
    component._wsStatus = 'open';
    component._ws = { send };
    component._input = '  hello agent  ';
    vi.spyOn(component, '_armTurnWatchdog').mockImplementation(() => undefined);

    await component._send();
    expect(send).toHaveBeenCalledWith({ type: 'message', text: 'hello agent' });
    expect(component._messages.at(-1)).toMatchObject({ role: 'user', text: 'hello agent' });
    expect(component._input).toBe('');
    expect(component._progressPhase).toBe('Thinking');

    component._wsStatus = 'closed';
    component._input = 'must not send';
    await component._send();
    expect(send).toHaveBeenCalledOnce();

    component._wsStatus = 'open';
    component._input = '';
    component._audioAttachment = makeFile('voice.wav', 'voice', 'audio/wav');
    const encode = vi.spyOn(component, '_fileToBase64').mockRejectedValue(new Error('reader failed'));
    await component._send();
    expect(component._attachmentError).toBe('Failed to encode audio attachment');
    expect(component._encodingAttachment).toBe(false);
    expect(send).toHaveBeenCalledOnce();

    encode.mockResolvedValue('YmFzZTY0');
    vi.stubGlobal('URL', { createObjectURL: vi.fn(() => 'blob:uploaded-audio') });
    component._input = 'summarize this';
    component._audioAttachment = makeFile('voice.wav', 'voice', 'audio/wav');
    await component._send();
    expect(send).toHaveBeenLastCalledWith({
      type: 'message', text: 'summarize this',
      attachment: { mimeType: 'audio/wav', fileName: 'voice.wav', data: 'YmFzZTY0' },
    });
    expect(component._messages.at(-1).audio).toMatchObject({ mimeType: 'audio/wav', url: 'blob:uploaded-audio' });
  });

  it('clears attachments and converts or replays audio URLs safely', () => {
    const { chat } = createChat();
    const component = chat as any;
    const createObjectURL = vi.fn().mockReturnValue('blob:audio');
    vi.stubGlobal('URL', { createObjectURL });

    expect(component._base64ToBlobUrl('YQ==', 'audio/wav')).toBe('blob:audio');
    createObjectURL.mockImplementation(() => { throw new Error('blob URLs unavailable'); });
    expect(component._base64ToBlobUrl('YQ==', 'audio/wav')).toBe('data:audio/wav;base64,YQ==');

    component._audioAttachment = makeFile('voice.wav', 'voice', 'audio/wav');
    component._attachmentError = 'previous error';
    component._clearAttachment();
    expect(component._audioAttachment).toBeNull();
    expect(component._attachmentError).toBe('');

    component._triggerFilePicker();
    const playedSources: string[] = [];
    vi.stubGlobal('Audio', class {
      constructor(source: string) { playedSources.push(source); }
      play() { return Promise.resolve(); }
    });
    component._replayAudio({ mimeType: 'audio/wav', data: 'YQ==' });
    expect(playedSources).toEqual(['data:audio/wav;base64,YQ==']);
  });

  it('marks approval resolution, stops a turn, and resets the next session', () => {
    const { chat } = createChat();
    const component = chat as any;
    const armWatchdog = vi.spyOn(component, '_armTurnWatchdog').mockImplementation(() => undefined);
    const message = { id: 'approval-message', role: 'assistant', text: 'pending', timestamp: 1, isApprovalPrompt: true };
    component._messages = [message];
    component._awaitingApproval = true;
    component._markApprovalResolved(message);
    expect(component._awaitingApproval).toBe(false);
    expect(component._messages[0]).toMatchObject({ isApprovalPrompt: false, approvalResolved: true });
    expect(component._progressPhase).toBe('Approval resolved');
    expect(armWatchdog).toHaveBeenCalledOnce();

    const send = vi.fn();
    component._ws = { send };
    component._isTyping = true;
    component._sendStop();
    expect(send).toHaveBeenCalledWith({ type: 'message', text: '/stop' });
    expect(component._sessionHalted).toBe(true);
    expect(component._messages.at(-1)).toMatchObject({ role: 'user', text: '/stop' });

    const disconnect = vi.fn();
    component._ws = { disconnect };
    const connect = vi.spyOn(component, '_connect').mockResolvedValue(undefined);
    component._isTyping = true;
    component._awaitingApproval = true;
    component._turnStalled = true;
    component._sessionHalted = true;
    component._progressPhase = 'old progress';
    component.startNewSession('platform-new');
    expect(disconnect).toHaveBeenCalledOnce();
    expect(connect).toHaveBeenCalledOnce();
    expect(component.platformId).toBe('platform-new');
    expect(component._messages).toEqual([]);
    expect(component._progressPhase).toBe('');
    expect(component._isTyping).toBe(false);
    expect(sessionStorage.getItem('amsha-platform-id')).toBe('platform-new');
  });

  it('covers update synchronization, delayed status events, markdown caching, and reconnect guards', async () => {
    const { chat } = createChat();
    const component = chat as any;
    const connect = vi.spyOn(component, '_connect').mockResolvedValue(undefined);
    component._appContext = { platformId: 'platform-1' };
    component.updated(new Map([['_appContext', undefined]]));
    expect(connect).toHaveBeenCalledOnce();

    component._connecting = false;
    component.traceEvents = [{ runId: 'run-1', eventId: 'trace-1' }];
    component._messages = [{ id: 'assistant-1', role: 'assistant', runId: 'run-1', text: 'answer', timestamp: 1 }];
    component.pendingApprovals = [{ taskId: 'task-1', sessionId: 'session-1', toolName: 'search_web' }];
    component.updated(new Map([['traceEvents', undefined], ['pendingApprovals', undefined]]));
    expect(component._messages[0].runEvents).toEqual(component.traceEvents);

    component._appContext.sessionId = 'session-1';
    component._isTyping = true;
    vi.spyOn(component, '_loadHistory').mockResolvedValue([
      { role: 'assistant', text: 'complete', isApprovalPrompt: false },
    ]);
    component._onAgentSessionStatus(new CustomEvent('agent-session-status', {
      detail: { sessionId: 'other-session', status: 'COMPLETED' },
    }));
    expect(component._progressPhase).toBe('');
    component._onAgentSessionStatus(new CustomEvent('agent-session-status', {
      detail: { sessionId: 'session-1', status: 'COMPLETED' },
    }));
    await Promise.resolve();
    expect(component._isTyping).toBe(false);

    const empty = component._renderMarkdown('empty', '');
    expect(empty).toBeDefined();
    component._markdownCache.set('cached', '<p>already sanitized</p>');
    expect(component._renderMarkdown('cached', 'ignored because cached')).toBeDefined();

    component._lastConnectAt = Date.now();
    component._intentionalClose = false;
    component._connecting = true;
    await component._connectInternal();
    expect(component._connecting).toBe(false);
    component._lastConnectAt = 0;
    component._intentionalClose = true;
    await component._connectInternal();
    expect(component._connecting).toBe(false);
  });

  it('records a clear stalled-turn state and reports unsupported microphone capture', async () => {
    const { chat } = createChat();
    const component = chat as any;
    component._markTurnStalled('Response delayed', 'Check status before retrying.');
    component._markTurnStalled('Response delayed', 'Check status before retrying.');
    expect(component._turnStalled).toBe(true);
    expect(component._progressTrail).toHaveLength(1);

    vi.stubGlobal('navigator', { mediaDevices: undefined });
    await component._startRecording();
    expect(component._attachmentError).toContain('not supported');
    expect(component._isRecording).toBe(false);
  });

  it('extracts base64 payloads from FileReader results', async () => {
    const { chat } = createChat();
    const component = chat as any;
    vi.stubGlobal('FileReader', class {
      result: string | null = null;
      error: Error | null = null;
      onload: (() => void) | null = null;
      onerror: (() => void) | null = null;
      readAsDataURL() {
        this.result = 'data:audio/wav;base64,Zm9v';
        this.onload?.();
      }
    });
    const payload = await component._fileToBase64(makeFile('voice.wav', 'voice', 'audio/wav'));
    expect(payload).toBe('Zm9v');
  });

  it('coalesces concurrent connects and suppresses reconnect after intentional shutdown', async () => {
    const { chat } = createChat();
    const component = chat as any;
    let finishConnect: (() => void) | undefined;
    const connectInternal = vi.spyOn(component, '_connectInternal').mockImplementation(
      () => new Promise<void>((resolve) => { finishConnect = resolve; }),
    );
    const first = component._connect();
    const second = component._connect();
    expect(connectInternal).toHaveBeenCalledOnce();
    finishConnect?.();
    await Promise.all([first, second]);
    expect(component._connectPromise).toBeNull();

    component._intentionalClose = true;
    component._scheduleReconnect();
    expect(component._reconnectTimeoutId).toBeNull();
  });

  it('handles pending approval actions and cleans up inactive recordings', async () => {
    const { chat } = createChat();
    const component = chat as any;
    const armWatchdog = vi.spyOn(component, '_armTurnWatchdog').mockImplementation(() => undefined);
    component._awaitingApproval = true;
    component._showDenyReason = true;
    component._onPendingApprovalAction(new CustomEvent('approval-action', { detail: { taskId: 'task-1', action: 'approved' } }));
    expect(component._progressPhase).toBe('Executing tool');
    expect(component._awaitingApproval).toBe(false);
    expect(component._showDenyReason).toBe(false);
    expect(armWatchdog).toHaveBeenCalledOnce();

    component._onPendingApprovalAction(new CustomEvent('approval-action', { detail: { taskId: 'task-1', action: 'rejected' } }));
    expect(component._progressPhase).toBe('Approval denied');
    expect(component._isTyping).toBe(false);

    const stopRecording = vi.fn();
    component._isRecording = true;
    component._mediaRecorder = { state: 'recording', stop: stopRecording };
    await component._toggleRecording();
    expect(stopRecording).toHaveBeenCalledOnce();
    component._mediaRecorder = { state: 'inactive', stop: vi.fn() };
    component._recordingStream = { getTracks: () => [{ stop: stopRecording }] };
    component._stopRecording();
    expect(component._isRecording).toBe(false);
    expect(component._recordingStream).toBeNull();
  });
});
