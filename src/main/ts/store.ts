import {t} from './i18n';
export interface ConversationMessage {
  kind: string;
  title: string;
  body: string;
  itemId?: string;
  elapsedMs?: number;
  createdAtEpochMs?: number;
  fileReferencePaths?: string[];
}

export interface BridgeState {
  [key: string]: unknown;
  messages: ConversationMessage[];
  connected?: boolean;
  busy?: boolean;
  queuedCount?: number;
  queuedInputs?: Array<{id: string; text: string; attachments?: string[]; context?: string}>;
  canSteer?: boolean;
  editingQueuedInputId?: string | null;
  title?: unknown;
  threadId?: unknown;
  historyReadOnly?: boolean;
  history?: unknown[];
  historyProvider?: string;
  customModels?: unknown[];
  changes?: unknown[];
  attachments?: unknown[];
  fileReferences?: unknown[];
  providers?: unknown[];
  skills?: unknown[];
  skillErrors?: unknown[];
  usagePercentage?: number;
  usageUsedTokens?: number;
  usageMaxTokens?: number;
  visibleMessageCount?: number;
  view?: string;
}

export interface ReducedEvent {
  type: string;
  [key: string]: unknown;
}

export interface Store<TState, TEvent> {
  getState(): TState;
  dispatch(event: TEvent): TState;
}

export function copyStateFields(
  target: Record<string, unknown>,
  source: Record<string, unknown>,
  keys: readonly string[],
): void {
  keys.forEach(key => {
    target[key] = source[key];
  });
}

export function createStore<TState, TEvent>(
  initialState: TState,
  reducer: (state: TState, event: TEvent) => TState,
): Store<TState, TEvent> {
  let state = initialState;
  return {
    getState: () => state,
    dispatch(event) {
      state = reducer(state, event);
      return state;
    },
  };
}

export function reduceBridgeEvent(state: BridgeState, event: ReducedEvent): BridgeState {
  switch (event.type) {
    case 'bootstrap': {
      const snapshot = {...object(event.state)};
      // Only fileContext events update the workspace editor. A delayed settings
      // snapshot can contain an empty or older file from before a tab switch.
      for (const key of ['activeFile', 'activeFileStartLine', 'activeFileEndLine', 'activeFileSelectionLabel']) {
        delete snapshot[key];
      }
      Object.assign(state, snapshot);
      state.messages = state.messages.map(message => withCreatedAt(message));
      break;
    }
    case 'connection':
      state.connected = Boolean(event.connected);
      break;
    case 'busy':
      state.busy = Boolean(event.busy);
      if (!state.busy) state.canSteer = false;
      // Fall through: busy updates carry the same queue snapshot as queue updates.
    case 'queue':
      state.queuedCount = nonNegative(event.queuedCount);
      if (Array.isArray(event.queuedInputs)) state.queuedInputs = event.queuedInputs;
      else if (!state.queuedCount) state.queuedInputs = [];
      if (typeof event.canSteer === 'boolean') state.canSteer = event.canSteer;
      if ('editingQueuedInputId' in event) state.editingQueuedInputId = string(event.editingQueuedInputId) || null;
      break;
    case 'clear':
      if (!event.preserveQueue) {
        state.queuedInputs = [];
        state.queuedCount = 0;
        state.canSteer = false;
        state.editingQueuedInputId = null;
        state.queueEdit = null;
      }
      state.messages = [];
      state.title = string(event.title) || t('新会话');
      state.threadId = event.threadId || null;
      state.historyReadOnly = false;
      state.usagePercentage = 0;
      state.usageUsedTokens = 0;
      state.usageMaxTokens = 0;
      state.visibleMessageCount = 100;
      break;
    case 'message':
      state.messages.push(withCreatedAt(event.entry as ConversationMessage));
      break;
    case 'replaceMessage':
      replaceMessage(state.messages, event.entry as ConversationMessage);
      break;
    case 'appendMessage':
      appendMessage(state.messages, event);
      break;
    case 'history':
      state.history = array(event.items);
      state.historyProvider = string(event.provider);
      break;
    case 'changes': state.changes = array(event.items); break;
    case 'attachments': state.attachments = array(event.items); break;
    case 'fileReferences': state.fileReferences = array(event.items); break;
    case 'providers': state.providers = array(event.items); break;
    case 'skills':
      state.skills = array(event.items);
      state.skillErrors = array(event.errors);
      break;
    case 'usage':
      state.usagePercentage = number(event.percentage);
      state.usageUsedTokens = number(event.usedTokens);
      state.usageMaxTokens = number(event.maxTokens);
      break;
    case 'thread':
      if (event.titleOnly) {
        state.title = string(event.title) || state.title;
        break;
      }
      state.threadId = event.id || null;
      state.historyReadOnly = Boolean(event.historyReadOnly);
      state.title = string(event.title) || state.title;
      state.view = 'chat';
      break;
    default:
      break;
  }
  return state;
}

export function isProviderChange(currentProvider: unknown, requestedProvider: unknown): boolean {
  return normalizeProvider(currentProvider) !== normalizeProvider(requestedProvider);
}

export interface StreamAppendEvent extends ReducedEvent {
  type: 'appendMessage';
  sessionId: string;
  itemId: string;
  delta: string;
}

export function createStreamBatcher(
  schedule: (flush: () => void) => void,
  sink: (event: StreamAppendEvent) => void,
) {
  const pending = new Map<string, StreamAppendEvent>();
  let scheduled = false;

  const flush = () => {
    scheduled = false;
    const events = [...pending.values()];
    pending.clear();
    events.forEach(sink);
  };

  return {
    push(event: StreamAppendEvent) {
      const key = `${event.sessionId}\0${event.itemId}`;
      const current = pending.get(key);
      pending.set(key, current ? {...event, delta: current.delta + event.delta} : {...event});
      if (scheduled) return;
      scheduled = true;
      schedule(flush);
    },
    flush,
    size: () => pending.size,
  };
}

function replaceMessage(messages: ConversationMessage[], entry: ConversationMessage) {
  const index = messages.findIndex(message => message.itemId === entry.itemId);
  if (index >= 0) messages[index] = withCreatedAt(entry, messages[index].createdAtEpochMs);
  else messages.push(withCreatedAt(entry));
}

function appendMessage(messages: ConversationMessage[], event: ReducedEvent) {
  const itemId = string(event.itemId);
  const index = messages.findIndex(message => message.itemId === itemId);
  if (index >= 0) {
    messages[index] = {...messages[index], body: messages[index].body + string(event.delta)};
    return;
  }
  messages.push({
    kind: string(event.kind) || 'assistant',
    title: string(event.title) || 'Codex',
    body: string(event.delta),
    itemId,
    createdAtEpochMs: positiveNumber(event.createdAtEpochMs) || Date.now(),
  });
}

function withCreatedAt(message: ConversationMessage, fallback?: number): ConversationMessage {
  const createdAtEpochMs = positiveNumber(message.createdAtEpochMs) || positiveNumber(fallback) || Date.now();
  return message.createdAtEpochMs === createdAtEpochMs ? message : {...message, createdAtEpochMs};
}

function object(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
}

function array(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function string(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function normalizeProvider(value: unknown): string {
  return value === 'claude' ? 'claude' : 'codex';
}

function number(value: unknown): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function nonNegative(value: unknown): number {
  return Math.max(0, number(value));
}

function positiveNumber(value: unknown): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
}
