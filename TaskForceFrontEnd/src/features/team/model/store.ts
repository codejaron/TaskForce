import { create } from 'zustand';
import { api } from '../../../shared/api';
import { fetchEventSource } from '@microsoft/fetch-event-source';
import type {
  Session,
  TeamSessionHistoryDTO,
  TeamHistoryMessageDTO,
  TeamHistoryToolCallDTO,
  Message as ApiMessage,
  ToolCallDTO,
  RuntimeLifecycleStatus,
  WorkerInstance
} from '../../../shared/api/types';
import { apiUrl } from '../../../shared/api/base';

// ========== 类型定义 ==========

export interface TeamMember {
  instanceId: string;
  agentName: string;
  status: 'IDLE' | 'WORKING' | 'WAITING' | 'WAITING_REPLY' | 'SHUTDOWN';
  lifecycleStatus: RuntimeLifecycleStatus;
  loopRunning: boolean;
  currentTask?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface TaskItem {
  taskId: number;
  subject: string;
  description?: string;
  completionNote?: string;
  status: 'PENDING' | 'ASSIGNED' | 'WORKING' | 'COMPLETED' | 'FAILED';
  owner?: string;
  blockedBy: number[];
  blocks: number[];
}

export interface WorkerMessage {
  id: string;
  type: 'thinking' | 'tool_call' | 'tool_result' | 'output' | 'system' | 'user' | 'error';
  content: string;
  timestamp: string;
  toolName?: string;
  toolCallId?: string;
  serverName?: string;
  toolArgs?: string;
  toolResult?: string;
  toolStatus?: 'RUNNING' | 'SUCCESS' | 'FAILED';
  errorMessage?: string;
  durationMs?: number;
}

export interface LeadMessage {
  id: string;
  type: 'system' | 'lead' | 'worker' | 'user' | 'tool_call' | 'tool_result';
  content: string;
  timestamp: string;
  agentName?: string;
  workerId?: string;
  instanceId?: string;
  toolName?: string;
  toolCallId?: string;
  serverName?: string;
  toolArgs?: string;
  toolResult?: string;
  toolStatus?: 'RUNNING' | 'SUCCESS' | 'FAILED';
  errorMessage?: string;
  durationMs?: number;
}

export type LeadStatus = 'idle' | 'active' | 'shutdown';
export type TeamPhase = 'not_started' | 'active' | 'shutting_down' | 'closed';

interface TeamState {
  // Session 管理
  sessions: Session[];
  currentSession: Session | null;

  // Team 状态
  teamId: string | null;
  leadStatus: LeadStatus;
  leadLifecycleStatus: RuntimeLifecycleStatus;
  members: TeamMember[];
  teamPhase: TeamPhase;
  messages: LeadMessage[];
  isConnected: boolean;
  error: string | null;
  isTeamStarted: boolean;

  // Task Board
  tasks: TaskItem[];

  // Worker 对话
  workerMessages: Record<string, WorkerMessage[]>;
  activeWorkerId: string | null;
  workerConnections: Record<string, AbortController>;

  // Actions
  fetchSessions: () => Promise<void>;
  createSession: (name: string, agentIds: number[]) => Promise<void>;
  selectSession: (session: Session) => void;
  deleteSession: (sessionId: string) => Promise<void>;
  sendToLead: (message: string) => Promise<void>;
  stopTeam: () => Promise<void>;
  disconnectStream: () => void;
  setActiveWorker: (instanceId: string | null) => void;
  connectWorkerStream: (instanceId: string) => void;
  disconnectWorkerStream: (instanceId: string) => void;
  disconnectAllWorkerStreams: () => void;
  sendToWorker: (instanceId: string, message: string) => Promise<void>;
  syncRuntimeStatus: () => Promise<void>;
}

// ========== SSE 连接管理 ==========

let currentAbortController: AbortController | null = null;
let reconnectTimeoutId: ReturnType<typeof setTimeout> | null = null;
let selectSessionEpoch = 0;
let runtimeStatusPollTimer: ReturnType<typeof setInterval> | null = null;

const RECONNECT_CONFIG = {
  maxRetries: 5,
  initialDelay: 1000,
  maxDelay: 32000,
  backoffMultiplier: 2
};

function getReconnectDelay(retryCount: number): number {
  const baseDelay = RECONNECT_CONFIG.initialDelay *
                    Math.pow(RECONNECT_CONFIG.backoffMultiplier, retryCount);
  const jitter = Math.random() * 1000;
  return Math.min(baseDelay + jitter, RECONNECT_CONFIG.maxDelay);
}

function parseTaskLinks(input: unknown): number[] {
  if (!Array.isArray(input)) {
    return [];
  }
  return input
    .map(value => {
      if (typeof value === 'number') return value;
      if (typeof value === 'string') {
        const parsed = Number.parseInt(value, 10);
        return Number.isFinite(parsed) ? parsed : null;
      }
      return null;
    })
    .filter((value): value is number => value !== null);
}

function normalizeTaskStatus(raw: unknown): TaskItem['status'] {
  if (raw === 'PENDING' || raw === 'ASSIGNED' || raw === 'WORKING' || raw === 'COMPLETED' || raw === 'FAILED') {
    return raw;
  }
  if (raw === 'IN_PROGRESS') {
    return 'WORKING';
  }
  return 'PENDING';
}

function parseTaskItem(input: unknown): TaskItem | null {
  if (!input || typeof input !== 'object') {
    return null;
  }

  const data = input as Record<string, unknown>;
  const rawTaskId = data.taskId;
  const taskId = typeof rawTaskId === 'number'
    ? rawTaskId
    : typeof rawTaskId === 'string'
      ? Number.parseInt(rawTaskId, 10)
      : Number.NaN;

  if (!Number.isFinite(taskId)) {
    return null;
  }

  const subject = typeof data.subject === 'string' ? data.subject : '';
  const description = typeof data.description === 'string' ? data.description : '';
  const completionNote = typeof data.completionNote === 'string' ? data.completionNote : undefined;
  const owner = typeof data.owner === 'string' && data.owner.trim() ? data.owner : undefined;
  const blockedBy = parseTaskLinks(data.blockedBy);
  const blocks = parseTaskLinks(data.blocks);

  return {
    taskId,
    subject,
    description,
    completionNote,
    owner,
    blockedBy,
    blocks,
    status: normalizeTaskStatus(data.status)
  };
}

function sortTasksById(tasks: TaskItem[]): TaskItem[] {
  return [...tasks].sort((a, b) => a.taskId - b.taskId);
}

function normalizeWorkerStatus(raw: unknown): TeamMember['status'] {
  if (raw === 'IDLE' || raw === 'WORKING' || raw === 'WAITING' || raw === 'WAITING_REPLY' || raw === 'SHUTDOWN') {
    return raw;
  }
  return 'IDLE';
}

function normalizeLifecycleStatus(raw: unknown): RuntimeLifecycleStatus | null {
  if (raw === 'RUNNING' || raw === 'STOPPED' || raw === 'DESTROYED') {
    return raw;
  }
  return null;
}

function mapWorkerLifecycleStatus(status: TeamMember['status'], loopRunning: boolean, explicitLifecycle?: unknown): RuntimeLifecycleStatus {
  const explicit = normalizeLifecycleStatus(explicitLifecycle);
  if (explicit) {
    return explicit;
  }
  if (status === 'SHUTDOWN') {
    return 'DESTROYED';
  }
  return loopRunning ? 'RUNNING' : 'STOPPED';
}

function toLeadStatus(lifecycleStatus: RuntimeLifecycleStatus): LeadStatus {
  if (lifecycleStatus === 'RUNNING') {
    return 'active';
  }
  if (lifecycleStatus === 'DESTROYED') {
    return 'shutdown';
  }
  return 'idle';
}

function parseInboxSenders(toolResult: string): string[] {
  if (!toolResult || !toolResult.trim()) {
    return [];
  }
  const matches = [...toolResult.matchAll(/From:\s*([^\n,]+)/gi)];
  const senders = matches
    .map(match => match[1]?.trim())
    .filter((sender): sender is string => Boolean(sender));
  return [...new Set(senders)];
}

function buildInboxReadHint(readBy: 'Lead' | 'Worker', senders: string[]): string {
  if (senders.length === 0) {
    return `${readBy} 读取了收件箱，但没有新消息`;
  }
  return `${readBy} 读取到来自 ${senders.join('、')} 的消息`;
}

function isLikelyToolInvocationContent(content: string): boolean {
  const trimmed = content.trim();
  if (!trimmed) {
    return false;
  }

  const compact = trimmed.replace(/\s+/g, '');
  if (/^(\[\{"name":"[^"]+"\}\])+$/.test(compact)) {
    return true;
  }
  if (/^(\{"name":"[^"]+"\})+$/.test(compact)) {
    return true;
  }

  try {
    const parsed = JSON.parse(trimmed) as unknown;
    if (Array.isArray(parsed) && parsed.length > 0) {
      return parsed.every(item => {
        if (!item || typeof item !== 'object') {
          return false;
        }
        const record = item as Record<string, unknown>;
        return typeof record.name === 'string';
      });
    }
    if (parsed && typeof parsed === 'object') {
      const record = parsed as Record<string, unknown>;
      return typeof record.name === 'string';
    }
  } catch {
    return false;
  }

  return false;
}

function clearRuntimeStatusPolling() {
  if (runtimeStatusPollTimer) {
    clearInterval(runtimeStatusPollTimer);
    runtimeStatusPollTimer = null;
  }
}

// ========== 事件去重 ==========

const processedEventIds = new Map<string, Set<string>>();

function getEventIdSet(sessionId: string): Set<string> {
  if (!processedEventIds.has(sessionId)) {
    processedEventIds.set(sessionId, new Set<string>());
  }
  return processedEventIds.get(sessionId)!;
}

function clearEventIdSet(sessionId: string) {
  processedEventIds.delete(sessionId);
}

const LAST_EVENT_ID_STORAGE_KEY = 'team:last-event-id-by-session';

function loadLastEventIdBySession(): Record<string, string> {
  if (typeof window === 'undefined') {
    return {};
  }
  try {
    const raw = window.sessionStorage.getItem(LAST_EVENT_ID_STORAGE_KEY);
    if (!raw) {
      return {};
    }
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed as Record<string, string> : {};
  } catch {
    return {};
  }
}

let lastEventIdBySession: Record<string, string> = loadLastEventIdBySession();

function persistLastEventIdBySession() {
  if (typeof window === 'undefined') {
    return;
  }
  try {
    window.sessionStorage.setItem(LAST_EVENT_ID_STORAGE_KEY, JSON.stringify(lastEventIdBySession));
  } catch {
    // ignore persistence failure
  }
}

function getLastEventId(sessionId: string): string | undefined {
  const id = lastEventIdBySession[sessionId];
  return id && id.trim() ? id : undefined;
}

function saveLastEventId(sessionId: string, eventId: string) {
  if (!sessionId || !eventId || !eventId.trim()) {
    return;
  }
  if (lastEventIdBySession[sessionId] === eventId) {
    return;
  }
  lastEventIdBySession = {
    ...lastEventIdBySession,
    [sessionId]: eventId
  };
  persistLastEventIdBySession();
}

function removeLastEventId(sessionId: string) {
  if (!lastEventIdBySession[sessionId]) {
    return;
  }
  const next = { ...lastEventIdBySession };
  delete next[sessionId];
  lastEventIdBySession = next;
  persistLastEventIdBySession();
}

function toTimestampIso(value?: string): string {
  if (!value) {
    return new Date().toISOString();
  }
  const ms = Date.parse(value);
  if (Number.isNaN(ms)) {
    return new Date().toISOString();
  }
  return new Date(ms).toISOString();
}

function toTimeValue(value?: string): number {
  if (!value) {
    return 0;
  }
  const ms = Date.parse(value);
  return Number.isNaN(ms) ? 0 : ms;
}

function toUserFriendlySystemContent(content: string): string {
  const trimmed = content.trim();
  if (trimmed.startsWith('团队已启动') || /^team started\b/i.test(trimmed)) {
    return '团队已启动';
  }
  return content;
}

function extractWorkerInstanceIdFromAgentName(agentName?: string): string | null {
  if (!agentName) {
    return null;
  }
  if (agentName.startsWith('worker:')) {
    const instanceId = agentName.slice('worker:'.length).trim();
    return instanceId || null;
  }
  return null;
}

function deriveWorkerDisplayName(instanceId?: string | null): string {
  if (!instanceId) {
    return 'Worker';
  }
  const suffix = instanceId.match(/_w(\d+)$/i);
  if (suffix) {
    return `Worker #${suffix[1]}`;
  }
  if (instanceId.length > 18) {
    return `Worker ${instanceId.slice(-6)}`;
  }
  return `Worker ${instanceId}`;
}

function toLeadMessageFromHistory(item: TeamHistoryMessageDTO): LeadMessage | null {
  const timestamp = toTimestampIso(item.createdAt);
  if (item.messageType === 'TEAM_USER' && item.agentName?.startsWith('worker:')) {
    return null;
  }
  if (item.messageType === 'TEAM_USER') {
    return {
      id: `history_msg_${item.id}`,
      type: 'user',
      content: item.content || '',
      timestamp
    };
  }
  if (item.messageType === 'TEAM_LEAD') {
    if (isLikelyToolInvocationContent(item.content || '')) {
      return null;
    }
    return {
      id: `history_msg_${item.id}`,
      type: 'lead',
      content: item.content || '',
      timestamp,
      agentName: item.agentName || 'Lead'
    };
  }
  if (item.messageType === 'TEAM_WORKER') {
    // Worker 输出仅在 Worker 面板展示，避免在 Lead 面板重复造成“误发送给 Lead”的歧义。
    return null;
  }
  if (item.messageType === 'TEAM_SYSTEM') {
    return {
      id: `history_msg_${item.id}`,
      type: 'system',
      content: toUserFriendlySystemContent(item.content || ''),
      timestamp
    };
  }
  return null;
}

function toWorkerOutputMessageFromHistory(item: TeamHistoryMessageDTO): { instanceId: string; message: WorkerMessage } | null {
  if (item.messageType !== 'TEAM_WORKER') {
    return null;
  }
  const instanceId = extractWorkerInstanceIdFromAgentName(item.agentName);
  if (!instanceId) {
    return null;
  }
  return {
    instanceId,
    message: {
      id: `history_msg_${item.id}`,
      type: 'output',
      content: item.content || '',
      timestamp: toTimestampIso(item.createdAt)
    }
  };
}

function toWorkerUserMessageFromHistory(item: TeamHistoryMessageDTO): { instanceId: string; message: WorkerMessage } | null {
  if (item.messageType !== 'TEAM_USER') {
    return null;
  }
  const agentName = item.agentName || '';
  if (!agentName.startsWith('worker:')) {
    return null;
  }
  const instanceId = agentName.slice('worker:'.length).trim();
  if (!instanceId) {
    return null;
  }
  return {
    instanceId,
    message: {
      id: `history_msg_${item.id}`,
      type: 'user',
      content: item.content || '',
      timestamp: toTimestampIso(item.createdAt)
    }
  };
}

function toLeadToolMessageFromHistory(item: TeamHistoryToolCallDTO): LeadMessage {
  const timestamp = toTimestampIso(item.completedAt || item.startedAt);
  const isRunning = item.status === 'RUNNING';
  return {
    id: `history_tool_${item.toolCallId}`,
    type: isRunning ? 'tool_call' : 'tool_result',
    content: isRunning
      ? (item.toolArgs || '{}')
      : (item.status === 'FAILED'
          ? `❌ ${item.errorMessage || item.toolResult || 'Tool call failed'}`
          : (item.toolResult || 'Tool call completed')),
    timestamp,
    agentName: 'Lead',
    toolName: item.toolName,
    toolCallId: item.toolCallId,
    serverName: item.serverName,
    toolArgs: item.toolArgs,
    toolResult: item.toolResult,
    toolStatus: isRunning ? 'RUNNING' : (item.status === 'FAILED' ? 'FAILED' : 'SUCCESS'),
    errorMessage: item.errorMessage,
    durationMs: item.durationMs
  };
}

function toWorkerToolMessageFromHistory(item: TeamHistoryToolCallDTO): WorkerMessage {
  const isRunning = item.status === 'RUNNING';
  return {
    id: `history_tool_${item.toolCallId}`,
    type: isRunning ? 'tool_call' : 'tool_result',
    content: isRunning
      ? (item.toolArgs || '{}')
      : (item.status === 'FAILED'
          ? `❌ ${item.errorMessage || item.toolResult || 'Tool call failed'}`
          : (item.toolResult || 'Tool call completed')),
    timestamp: toTimestampIso(item.completedAt || item.startedAt),
    toolName: item.toolName,
    toolCallId: item.toolCallId,
    serverName: item.serverName,
    toolArgs: item.toolArgs,
    toolResult: item.toolResult,
    toolStatus: isRunning ? 'RUNNING' : (item.status === 'FAILED' ? 'FAILED' : 'SUCCESS'),
    errorMessage: item.errorMessage,
    durationMs: item.durationMs
  };
}

function buildHistoryState(history: TeamSessionHistoryDTO | null | undefined): {
  leadMessages: LeadMessage[];
  workerMessages: Record<string, WorkerMessage[]>;
} {
  const leadMessages: LeadMessage[] = [];
  const workerMessages: Record<string, WorkerMessage[]> = {};
  const historyMessages = Array.isArray(history?.messages) ? history.messages : [];
  const historyToolCalls = Array.isArray(history?.toolCalls) ? history.toolCalls : [];

  historyMessages.forEach(item => {
    const workerUserMessage = toWorkerUserMessageFromHistory(item);
    if (workerUserMessage) {
      const existing = workerMessages[workerUserMessage.instanceId] || [];
      workerMessages[workerUserMessage.instanceId] = [...existing, workerUserMessage.message];
      return;
    }
    const workerOutputMessage = toWorkerOutputMessageFromHistory(item);
    if (workerOutputMessage) {
      const existing = workerMessages[workerOutputMessage.instanceId] || [];
      workerMessages[workerOutputMessage.instanceId] = [...existing, workerOutputMessage.message];
    }
    const leadMessage = toLeadMessageFromHistory(item);
    if (leadMessage) {
      leadMessages.push(leadMessage);
    }
  });

  historyToolCalls.forEach(item => {
    if (item.instanceId && item.instanceId.trim()) {
      const existing = workerMessages[item.instanceId] || [];
      workerMessages[item.instanceId] = [...existing, toWorkerToolMessageFromHistory(item)];
      return;
    }
    leadMessages.push(toLeadToolMessageFromHistory(item));
  });

  leadMessages.sort((a, b) => toTimeValue(a.timestamp) - toTimeValue(b.timestamp));
  Object.keys(workerMessages).forEach(instanceId => {
    workerMessages[instanceId] = [...workerMessages[instanceId]]
      .sort((a, b) => toTimeValue(a.timestamp) - toTimeValue(b.timestamp));
  });

  return { leadMessages, workerMessages };
}

function buildLegacyHistoryState(messages: ApiMessage[] | null | undefined, toolCalls: ToolCallDTO[] | null | undefined): {
  leadMessages: LeadMessage[];
  workerMessages: Record<string, WorkerMessage[]>;
} {
  const leadMessages: LeadMessage[] = [];
  const workerMessages: Record<string, WorkerMessage[]> = {};
  const historyMessages = Array.isArray(messages) ? messages : [];
  const historyToolCalls = Array.isArray(toolCalls) ? toolCalls : [];

  historyMessages.forEach(item => {
    const historyLike: TeamHistoryMessageDTO = {
      id: item.id,
      role: (item.role as 'user' | 'assistant' | 'system') || 'assistant',
      messageType: (item.messageType as TeamHistoryMessageDTO['messageType']) || 'TEAM_SYSTEM',
      agentName: item.agentName,
      content: item.content || '',
      createdAt: item.createdAt
    };

    const workerUserMessage = toWorkerUserMessageFromHistory(historyLike);
    if (workerUserMessage) {
      const existing = workerMessages[workerUserMessage.instanceId] || [];
      workerMessages[workerUserMessage.instanceId] = [...existing, workerUserMessage.message];
      return;
    }

    const workerOutputMessage = toWorkerOutputMessageFromHistory(historyLike);
    if (workerOutputMessage) {
      const existing = workerMessages[workerOutputMessage.instanceId] || [];
      workerMessages[workerOutputMessage.instanceId] = [...existing, workerOutputMessage.message];
    }

    const leadMessage = toLeadMessageFromHistory(historyLike);
    if (leadMessage) {
      leadMessages.push(leadMessage);
    }
  });

  historyToolCalls.forEach(item => {
    const historyLike: TeamHistoryToolCallDTO = {
      toolCallId: item.toolCallId,
      stepId: item.stepId,
      sequence: item.sequence,
      instanceId: item.instanceId,
      toolName: item.toolName,
      serverName: item.serverName,
      toolArgs: item.toolArgs,
      toolResult: item.toolResult,
      status: item.status,
      errorMessage: item.errorMessage,
      durationMs: item.durationMs,
      startedAt: item.startedAt,
      completedAt: item.completedAt
    };

    if (historyLike.instanceId && historyLike.instanceId.trim()) {
      const existing = workerMessages[historyLike.instanceId] || [];
      workerMessages[historyLike.instanceId] = [...existing, toWorkerToolMessageFromHistory(historyLike)];
      return;
    }
    leadMessages.push(toLeadToolMessageFromHistory(historyLike));
  });

  leadMessages.sort((a, b) => toTimeValue(a.timestamp) - toTimeValue(b.timestamp));
  Object.keys(workerMessages).forEach(instanceId => {
    workerMessages[instanceId] = [...workerMessages[instanceId]]
      .sort((a, b) => toTimeValue(a.timestamp) - toTimeValue(b.timestamp));
  });

  return { leadMessages, workerMessages };
}

function mergeLeadMessages(base: LeadMessage[], live: LeadMessage[]): LeadMessage[] {
  const merged = new Map<string, LeadMessage>();

  [...base, ...live].forEach(item => {
    const key = item.toolCallId
      ? `tool:${item.toolCallId}`
      : item.id
        ? `id:${item.id}`
        : `msg:${item.type}:${item.timestamp}:${item.content}`;
    const existing = merged.get(key);
    if (!existing) {
      merged.set(key, item);
      return;
    }
    merged.set(key, { ...existing, ...item });
  });

  return [...merged.values()].sort((a, b) => toTimeValue(a.timestamp) - toTimeValue(b.timestamp));
}

function mergeWorkerMessageList(base: WorkerMessage[], live: WorkerMessage[]): WorkerMessage[] {
  const merged = new Map<string, WorkerMessage>();
  [...base, ...live].forEach(item => {
    const key = item.toolCallId
      ? `tool:${item.toolCallId}`
      : item.id
        ? `id:${item.id}`
        : `msg:${item.type}:${item.timestamp}:${item.content}`;
    const existing = merged.get(key);
    if (!existing) {
      merged.set(key, item);
      return;
    }
    merged.set(key, { ...existing, ...item });
  });
  return [...merged.values()].sort((a, b) => toTimeValue(a.timestamp) - toTimeValue(b.timestamp));
}

function mergeWorkerMessageMaps(
  base: Record<string, WorkerMessage[]>,
  live: Record<string, WorkerMessage[]>
): Record<string, WorkerMessage[]> {
  const instanceIds = new Set<string>([
    ...Object.keys(base || {}),
    ...Object.keys(live || {})
  ]);

  const merged: Record<string, WorkerMessage[]> = {};
  instanceIds.forEach(instanceId => {
    const baseList = base?.[instanceId] || [];
    const liveList = live?.[instanceId] || [];
    merged[instanceId] = mergeWorkerMessageList(baseList, liveList);
  });
  return merged;
}

function parseWorkerIndexFromLabel(label?: string): number | null {
  if (!label) {
    return null;
  }
  const match = label.trim().match(/^worker-(\d+)$/i);
  if (!match) {
    return null;
  }
  const value = Number.parseInt(match[1], 10);
  return Number.isFinite(value) ? value : null;
}

function parseWorkerIndexFromInstanceId(instanceId?: string): number | null {
  if (!instanceId) {
    return null;
  }
  const match = instanceId.match(/_w(\d+)$/i);
  if (!match) {
    return null;
  }
  const value = Number.parseInt(match[1], 10);
  return Number.isFinite(value) ? value : null;
}

function resolveWorkerInstanceIdFromInbox(
  sessionId: string,
  from: string,
  fromInstanceId?: string,
  to?: string
): string | null {
  if (fromInstanceId && fromInstanceId.trim()) {
    return fromInstanceId.trim();
  }
  if (to && to.trim() && !to.endsWith('_lead')) {
    return to.trim();
  }
  const workerIndex = parseWorkerIndexFromLabel(from);
  if (workerIndex === null) {
    return null;
  }
  return `${sessionId}_w${workerIndex}`;
}

// ========== SSE 事件处理 ==========

function handleSSEEvent(
  ev: { event: string; data: string },
  sessionId: string,
  set: (partial: TeamState | Partial<TeamState> | ((state: TeamState) => TeamState | Partial<TeamState>), replace?: false) => void,
  get: () => TeamState
) {
  let eventData: unknown;

  try {
    eventData = JSON.parse(ev.data);
  } catch (e) {
    console.error('[Team] Failed to parse event data:', e);
    return;
  }

  const data = (eventData && typeof eventData === 'object') ? (eventData as Record<string, unknown>) : {};
  const eventType = (ev.event && ev.event.trim())
    || (typeof data.eventType === 'string' ? data.eventType : '')
    || (typeof data.type === 'string' ? data.type : '');

  if (!eventType) {
    console.warn('[Team] Missing SSE event type:', ev, data);
    return;
  }

  // 事件去重
  const eventId = data.eventId;
  if (typeof eventId === 'string' && eventId) {
    const eventIdSet = getEventIdSet(sessionId);
    if (eventIdSet.has(eventId)) {
      console.debug('[Team] Duplicate event ignored:', eventId, eventType);
      return;
    }
    eventIdSet.add(eventId);
    if (eventIdSet.size > 10000) {
      const firstId = eventIdSet.values().next().value;
      if (firstId !== undefined) {
        eventIdSet.delete(firstId);
      }
    }
  }

  const { messages, members, tasks } = get();

  switch (eventType) {
    case 'team_started':
      {
        const teamId = typeof data.teamId === 'string' ? data.teamId : null;
        const newMessage: LeadMessage = {
          id: `${Date.now()}_team_started`,
          type: 'system',
          content: '团队已启动',
          timestamp: new Date().toISOString()
        };
        set({
          teamId,
          teamPhase: 'active',
          leadLifecycleStatus: 'RUNNING',
          leadStatus: 'active',
          messages: [...messages, newMessage]
        });
        void get().syncRuntimeStatus();
      }
      break;

    case 'task_created':
      {
        const taskId = typeof data.taskId === 'number' ? data.taskId : 0;
        const subject = typeof data.subject === 'string' ? data.subject : '';
        const description = typeof data.description === 'string' ? data.description : '';
        const blockedBy = parseTaskLinks(data.blockedBy);
        const blocks = parseTaskLinks(data.blocks);

        const newTask: TaskItem = {
          taskId,
          subject,
          description,
          status: 'PENDING',
          blockedBy,
          blocks
        };

        const newMessage: LeadMessage = {
          id: `${Date.now()}_task_created`,
          type: 'system',
          content: `任务创建: #${taskId} ${subject}`,
          timestamp: new Date().toISOString()
        };

        const tasksWithDependencyUpdates = tasks.map(task => {
          if (!blockedBy.includes(task.taskId)) {
            return task;
          }

          const nextBlocks = task.blocks.includes(taskId)
            ? task.blocks
            : [...task.blocks, taskId];

          return { ...task, blocks: nextBlocks };
        });

        set({
          tasks: sortTasksById([...tasksWithDependencyUpdates, newTask]),
          messages: [...messages, newMessage]
        });
      }
      break;

    case 'task_claimed':
      {
        const taskId = typeof data.taskId === 'number' ? data.taskId : 0;
        const owner = typeof data.owner === 'string' ? data.owner : '';

        set({
          tasks: tasks.map(t =>
            t.taskId === taskId ? { ...t, status: 'ASSIGNED' as const, owner } : t
          )
        });
      }
      break;

    case 'task_completed':
      {
        const taskId = typeof data.taskId === 'number' ? data.taskId : 0;
        const completionNote = typeof data.completionNote === 'string' ? data.completionNote : undefined;

        set({
          tasks: tasks.map(t =>
            t.taskId === taskId
              ? { ...t, status: 'COMPLETED' as const, completionNote: completionNote ?? t.completionNote }
              : t
          )
        });
      }
      break;

    case 'task_failed':
      {
        const taskId = typeof data.taskId === 'number' ? data.taskId : 0;

        set({
          tasks: tasks.map(t =>
            t.taskId === taskId ? { ...t, status: 'FAILED' as const } : t
          )
        });
      }
      break;

    case 'task_unblocked':
      {
        const taskId = typeof data.taskId === 'number' ? data.taskId : 0;
        const unblockedBy = typeof data.unblockedBy === 'number' ? data.unblockedBy : null;

        set({
          tasks: tasks.map(t =>
            t.taskId === taskId
              ? {
                  ...t,
                  status: 'PENDING' as const,
                  blockedBy: unblockedBy === null ? t.blockedBy : t.blockedBy.filter(dep => dep !== unblockedBy)
                }
              : t
          )
        });
      }
      break;

    case 'worker_spawned':
      {
        const instanceId = typeof data.instanceId === 'string' ? data.instanceId : '';
        const agentName = typeof data.agentName === 'string' ? data.agentName
                        : typeof data.name === 'string' ? data.name : 'Unknown';

        const newMember: TeamMember = {
          instanceId,
          agentName,
          status: 'IDLE',
          lifecycleStatus: 'RUNNING',
          loopRunning: true,
          createdAt: new Date().toISOString()
        };

        const newMessage: LeadMessage = {
          id: `${Date.now()}_worker_spawned`,
          type: 'system',
          content: `Worker ${agentName} 已启动`,
          timestamp: new Date().toISOString(),
          agentName
        };

        set({
          members: [...members, newMember],
          messages: [...messages, newMessage]
        });

        // 自动为新 Worker 建立独立 SSE 连接
        setTimeout(() => get().connectWorkerStream(instanceId), 100);
        void get().syncRuntimeStatus();
      }
      break;

    case 'worker_report':
      {
        const workerId = typeof data.workerId === 'string' ? data.workerId : '';
        const agentName = typeof data.agentName === 'string' ? data.agentName : 'Worker';
        const content = typeof data.content === 'string' ? data.content : '';
        const status = normalizeWorkerStatus(data.status);

        const newMessage: LeadMessage = {
          id: `${Date.now()}_worker_report`,
          type: 'worker',
          content,
          timestamp: new Date().toISOString(),
          agentName,
          workerId
        };

        // 更新 worker 状态
        const updatedMembers = workerId
          ? members.map(m => m.instanceId === workerId
            ? {
                ...m,
                status,
                lifecycleStatus: mapWorkerLifecycleStatus(status, m.loopRunning, m.lifecycleStatus)
              }
            : m)
          : members;

        set({
          members: updatedMembers,
          messages: [...messages, newMessage]
        });
        void get().syncRuntimeStatus();
      }
      break;

    case 'broadcast':
      {
        const content = typeof data.content === 'string' ? data.content : '';
        const from = typeof data.from === 'string' ? data.from : 'System';

        const newMessage: LeadMessage = {
          id: `${Date.now()}_broadcast`,
          type: 'system',
          content: `[广播 from ${from}] ${content}`,
          timestamp: new Date().toISOString()
        };

        set({
          messages: [...messages, newMessage]
        });
      }
      break;

    case 'user_reply':
      {
        const content = typeof data.content === 'string' ? data.content : '';

        const newMessage: LeadMessage = {
          id: `${Date.now()}_user_reply`,
          type: 'user',
          content,
          timestamp: new Date().toISOString()
        };

        set({
          messages: [...messages, newMessage]
        });
      }
      break;

    case 'lead_message':
      {
        const content = typeof data.content === 'string' ? data.content : '';
        if (!content.trim() || isLikelyToolInvocationContent(content)) {
          break;
        }

        const newMessage: LeadMessage = {
          id: `${Date.now()}_lead_message`,
          type: 'lead',
          content,
          timestamp: new Date().toISOString(),
          agentName: 'Lead'
        };

        set({
          messages: [...messages, newMessage]
        });
      }
      break;

    case 'lead_output':
      {
        const output = typeof data.output === 'string'
          ? data.output
          : (typeof data.chunk === 'string' ? data.chunk : '');
        if (!output || isLikelyToolInvocationContent(output)) {
          break;
        }

        set(state => {
          const lastMessage = state.messages[state.messages.length - 1];
          if (
            lastMessage &&
            lastMessage.type === 'lead' &&
            lastMessage.id.endsWith('_lead_output_stream')
          ) {
            const mergedMessage: LeadMessage = {
              ...lastMessage,
              content: lastMessage.content + output,
              timestamp: new Date().toISOString()
            };
            return {
              messages: [
                ...state.messages.slice(0, -1),
                mergedMessage
              ]
            };
          }

          const newMessage: LeadMessage = {
            id: `${Date.now()}_lead_output_stream`,
            type: 'lead',
            content: output,
            timestamp: new Date().toISOString(),
            agentName: 'Lead'
          };

          return {
            messages: [...state.messages, newMessage]
          };
        });
      }
      break;

    case 'inbox_message':
      {
        const from = typeof data.from === 'string' ? data.from : '';
        const to = typeof data.to === 'string' ? data.to : '';
        const text = typeof data.text === 'string' ? data.text : '';
        const fromInstanceId = typeof data.fromInstanceId === 'string' ? data.fromInstanceId : undefined;
        if (!text.trim()) {
          break;
        }

        const normalizedFrom = from.trim().toLowerCase();
        if (normalizedFrom === 'user') {
          // 用户消息已由输入框乐观渲染 + controller 持久化回填，避免重复。
          break;
        }

        if (normalizedFrom.startsWith('worker-')) {
          const workerInstanceId = resolveWorkerInstanceIdFromInbox(sessionId, from, fromInstanceId, to);
          set(state => {
            if (!workerInstanceId) {
              return {};
            }
            const workerMessage: WorkerMessage = {
              id: `${Date.now()}_inbox_worker_echo_${from}`,
              type: 'output',
              content: text,
              timestamp: new Date().toISOString()
            };
            return {
              workerMessages: {
                ...state.workerMessages,
                [workerInstanceId]: [...(state.workerMessages[workerInstanceId] || []), workerMessage]
              }
            };
          });
          break;
        }

        if (normalizedFrom === 'lead' || normalizedFrom === 'team-lead') {
          const targetWorkerId = to && !to.endsWith('_lead') ? to : null;
          set(state => {
            const nextMessages = targetWorkerId
              ? [
                  ...state.messages,
                  {
                    id: `${Date.now()}_inbox_lead_to_worker`,
                    type: 'lead' as const,
                    content: `发给 ${deriveWorkerDisplayName(targetWorkerId)}: ${text}`,
                    timestamp: new Date().toISOString(),
                    agentName: 'Lead'
                  }
                ]
              : state.messages;

            if (!targetWorkerId) {
              return { messages: nextMessages };
            }

            const workerMessage: WorkerMessage = {
              id: `${Date.now()}_inbox_lead_instruction`,
              type: 'system',
              content: text,
              timestamp: new Date().toISOString()
            };
            return {
              messages: nextMessages,
              workerMessages: {
                ...state.workerMessages,
                [targetWorkerId]: [...(state.workerMessages[targetWorkerId] || []), workerMessage]
              }
            };
          });
        }
      }
      break;

    case 'tool_call_start':
      {
        const instanceId = typeof data.instanceId === 'string' && data.instanceId.trim()
          ? data.instanceId
          : undefined;
        // Worker 工具调用在右侧 Worker 面板展示，Lead 面板只展示 instanceId 为空的事件
        if (instanceId) {
          break;
        }

        const toolName = typeof data.toolName === 'string' ? data.toolName : 'unknown';
        const serverName = typeof data.serverName === 'string' ? data.serverName : undefined;
        const toolArgs = typeof data.toolArgs === 'string'
          ? data.toolArgs
          : JSON.stringify(data.toolArgs || {});
        const toolCallId = typeof data.toolCallId === 'string' ? data.toolCallId : '';

        const newMessage: LeadMessage = {
          id: `${Date.now()}_lead_tool_call_${toolCallId || toolName}`,
          type: 'tool_call',
          content: toolArgs,
          timestamp: new Date().toISOString(),
          agentName: 'Lead',
          toolName,
          toolCallId,
          serverName,
          toolArgs,
          toolStatus: 'RUNNING'
        };

        set(state => {
          if (toolCallId) {
            const existingIndex = state.messages.findIndex(
              msg => msg.toolCallId === toolCallId && (msg.type === 'tool_call' || msg.type === 'tool_result')
            );
            if (existingIndex >= 0) {
              const existing = state.messages[existingIndex];
              const merged: LeadMessage = {
                ...existing,
                ...newMessage,
                content: existing.content || newMessage.content,
                toolArgs: existing.toolArgs || newMessage.toolArgs
              };
              return {
                messages: [
                  ...state.messages.slice(0, existingIndex),
                  merged,
                  ...state.messages.slice(existingIndex + 1)
                ]
              };
            }
          }
          return { messages: [...state.messages, newMessage] };
        });
      }
      break;

    case 'tool_call_complete':
      {
        const instanceId = typeof data.instanceId === 'string' && data.instanceId.trim()
          ? data.instanceId
          : undefined;
        if (instanceId) {
          break;
        }

        const toolName = typeof data.toolName === 'string' ? data.toolName : 'unknown';
        const toolCallId = typeof data.toolCallId === 'string' ? data.toolCallId : '';
        const toolResult = typeof data.toolResult === 'string'
          ? data.toolResult
          : JSON.stringify(data.toolResult || {});
        const errorMessage = typeof data.errorMessage === 'string' ? data.errorMessage : '';
        const statusRaw = typeof data.status === 'string' ? data.status : 'SUCCESS';
        const toolStatus: LeadMessage['toolStatus'] = statusRaw === 'FAILED' ? 'FAILED' : 'SUCCESS';
        const isReadInboxCall = toolName === 'read_inbox' || toolName.endsWith('read_inbox');
        const shouldShowInboxHint = isReadInboxCall && toolStatus !== 'FAILED';
        const inboxSenders = shouldShowInboxHint ? parseInboxSenders(toolResult) : [];
        const inboxHintId = `lead_read_inbox_hint_${toolCallId || Date.now()}`;

        const newMessage: LeadMessage = {
          id: `${Date.now()}_lead_tool_result_${toolCallId || toolName}`,
          type: 'tool_result',
          content: toolStatus === 'FAILED'
            ? `❌ ${errorMessage || toolResult || 'Tool call failed'}`
            : (toolResult || 'Tool call completed'),
          timestamp: new Date().toISOString(),
          agentName: 'Lead',
          toolName,
          toolCallId,
          toolResult,
          toolStatus,
          errorMessage: errorMessage || undefined,
          durationMs: typeof data.durationMs === 'number' ? data.durationMs : undefined
        };

        set(state => {
          let nextMessages = state.messages;
          if (toolCallId) {
            const existingIndex = nextMessages.findIndex(
              msg => msg.toolCallId === toolCallId && (msg.type === 'tool_call' || msg.type === 'tool_result')
            );
            if (existingIndex >= 0) {
              const existing = nextMessages[existingIndex];
              const merged: LeadMessage = {
                ...existing,
                ...newMessage,
                toolArgs: newMessage.toolArgs ?? existing.toolArgs
              };
              nextMessages = [
                ...nextMessages.slice(0, existingIndex),
                merged,
                ...nextMessages.slice(existingIndex + 1)
              ];
            } else {
              nextMessages = [...nextMessages, newMessage];
            }
          } else {
            nextMessages = [...nextMessages, newMessage];
          }

          if (shouldShowInboxHint && !nextMessages.some(msg => msg.id === inboxHintId)) {
            nextMessages = [
              ...nextMessages,
              {
                id: inboxHintId,
                type: 'system',
                content: buildInboxReadHint('Lead', inboxSenders),
                timestamp: new Date().toISOString()
              }
            ];
          }

          return { messages: nextMessages };
        });
      }
      break;

    case 'team_shutdown':
      {
        const newMessage: LeadMessage = {
          id: `${Date.now()}_team_shutdown`,
          type: 'system',
          content: '团队已关闭',
          timestamp: new Date().toISOString()
        };

        set({
          teamPhase: 'closed',
          leadLifecycleStatus: 'DESTROYED',
          leadStatus: 'shutdown',
          isTeamStarted: false,
          messages: [...messages, newMessage]
        });
        void get().syncRuntimeStatus();
      }
      break;

    case 'session_complete':
      {
        set({
          teamPhase: 'closed',
          leadLifecycleStatus: 'DESTROYED',
          leadStatus: 'shutdown',
          isTeamStarted: false,
          members: members.map(member => ({
            ...member,
            status: 'SHUTDOWN',
            lifecycleStatus: 'DESTROYED',
            loopRunning: false
          }))
        });
        void get().syncRuntimeStatus();
      }
      break;

    case 'error':
      {
        const errorMsg = typeof data.error === 'string' ? data.error : 'Unknown error';
        set({
          error: errorMsg,
          leadLifecycleStatus: 'STOPPED',
          leadStatus: 'idle'
        });
      }
      break;

    default:
      console.warn('[Team] Unknown event type:', eventType, data);
  }
}

// ========== Zustand Store ==========

export const useTeamStore = create<TeamState>((set, get) => ({
  // Session 管理
  sessions: [],
  currentSession: null,

  // Team 状态
  teamId: null,
  leadStatus: 'idle',
  leadLifecycleStatus: 'STOPPED',
  members: [],
  teamPhase: 'not_started',
  messages: [],
  isConnected: false,
  error: null,
  isTeamStarted: false,

  // Task Board
  tasks: [],

  // Worker 对话
  workerMessages: {},
  activeWorkerId: null,
  workerConnections: {},

  fetchSessions: async () => {
    try {
      const teamSessions = await api.sessions.listByType('TEAM');
      set({ sessions: teamSessions });
    } catch (error: unknown) {
      console.error('[Team] Failed to fetch sessions:', error);
      set({ error: 'Failed to load sessions' });
    }
  },

  createSession: async (name: string, agentIds: number[]) => {
    try {
      const session = await api.sessions.create({
        name,
        type: 'TEAM',
        agentIds
      });

      // 添加到 sessions 列表并选中
      set(state => ({
        sessions: [...state.sessions, session]
      }));

      // 自动选中新创建的 session
      get().selectSession(session);
    } catch (error: unknown) {
      console.error('[Team] Failed to create session:', error);
      set({ error: 'Failed to create session' });
      throw error;
    }
  },

  selectSession: (session: Session) => {
    const currentSessionId = get().currentSession?.id;
    if (currentSessionId && currentSessionId === session.id) {
      console.debug('[Team] Skip selectSession for current session:', session.id);
      return;
    }

    const currentEpoch = ++selectSessionEpoch;
    console.log('[Team] Selecting session:', session.id, 'epoch=', currentEpoch);

    // 断开旧连接
    get().disconnectStream();

    const sessionId = session.id;

    set({
      currentSession: session,
      teamPhase: 'not_started',
      leadStatus: 'idle',
      leadLifecycleStatus: 'STOPPED',
      messages: [],
      members: [],
      tasks: [],
      workerMessages: {},
      activeWorkerId: null,
      workerConnections: {},
      error: null,
      isConnected: false,
      isTeamStarted: false,
      teamId: null
    });

    void (async () => {
      try {
        const [historyResult, taskBoardResult, workersResult] = await Promise.allSettled([
          api.team.getHistory(sessionId, 200),
          api.team.getTaskBoard(sessionId),
          api.team.getWorkers(sessionId)
        ]);

        const state = get();
        if (state.currentSession?.id !== sessionId || currentEpoch !== selectSessionEpoch) {
          console.debug('[Team] Skip stale hydration result:', { sessionId, currentEpoch, latest: selectSessionEpoch });
          return;
        }

        const nextState: Partial<TeamState> = {};
        let historyMessages: LeadMessage[] | undefined;
        let historyWorkerMessages: Record<string, WorkerMessage[]> | undefined;
        let historyWorkerInstanceIds: string[] = [];

        if (historyResult.status === 'fulfilled') {
          const history = historyResult.value;
          let { leadMessages, workerMessages } = buildHistoryState(history);

          if (leadMessages.length === 0 && Object.keys(workerMessages).length === 0) {
            const [legacyMessagesResult, legacyToolCallsResult] = await Promise.allSettled([
              api.messages.getBySession(sessionId),
              api.toolCalls.getBySession(sessionId)
            ]);
            if (legacyMessagesResult.status === 'fulfilled' || legacyToolCallsResult.status === 'fulfilled') {
              const legacy = buildLegacyHistoryState(
                legacyMessagesResult.status === 'fulfilled' ? legacyMessagesResult.value : [],
                legacyToolCallsResult.status === 'fulfilled' ? legacyToolCallsResult.value : []
              );
              leadMessages = legacy.leadMessages;
              workerMessages = legacy.workerMessages;
              console.debug('[Team] History fallback (legacy APIs) applied:', {
                sessionId,
                leadMessages: leadMessages.length,
                workerBuckets: Object.keys(workerMessages).length
              });
            }
          }

          historyMessages = leadMessages;
          historyWorkerMessages = workerMessages;
          historyWorkerInstanceIds = Object.keys(workerMessages);
          console.debug('[Team] History hydrated:', {
            sessionId,
            messageCount: leadMessages.length,
            workerCount: historyWorkerInstanceIds.length
          });
        } else {
          console.warn('[Team] History API rejected:', historyResult.reason);
        }

        if (taskBoardResult.status === 'fulfilled') {
          const raw = taskBoardResult.value as unknown;
          const rawTasks = Array.isArray(raw)
            ? raw
            : (raw && typeof raw === 'object' && Array.isArray((raw as { tasks?: unknown }).tasks))
              ? ((raw as { tasks: unknown[] }).tasks)
              : [];

          const normalizedTasks = rawTasks
            .map(task => parseTaskItem(task))
            .filter((task): task is TaskItem => task !== null);
          const runtimeTaskMap = new Map(state.tasks.map(task => [task.taskId, task]));
          const mergedTasks = normalizedTasks.map(snapshotTask => {
            const runtimeTask = runtimeTaskMap.get(snapshotTask.taskId);
            if (!runtimeTask) {
              return snapshotTask;
            }

            return {
              ...snapshotTask,
              status: runtimeTask.status,
              owner: runtimeTask.owner ?? snapshotTask.owner,
              description: runtimeTask.description || snapshotTask.description,
              completionNote: runtimeTask.completionNote ?? snapshotTask.completionNote
            };
          });

          state.tasks.forEach(runtimeTask => {
            if (!mergedTasks.some(task => task.taskId === runtimeTask.taskId)) {
              mergedTasks.push(runtimeTask);
            }
          });

          nextState.tasks = sortTasksById(mergedTasks);
        }

        if (workersResult.status === 'fulfilled') {
          nextState.members = workersResult.value.map(worker => {
            const status = normalizeWorkerStatus(worker.status);
            const loopRunning = Boolean(worker.loopRunning);
            const lifecycleStatus = mapWorkerLifecycleStatus(status, loopRunning, worker.lifecycleStatus);
            return {
              instanceId: worker.instanceId,
              agentName: worker.agentName || deriveWorkerDisplayName(worker.instanceId),
              status,
              lifecycleStatus,
              loopRunning,
              currentTask: typeof worker.currentTaskId === 'number' && worker.currentTaskId > 0
                ? String(worker.currentTaskId)
                : undefined,
              createdAt: worker.startedAt,
              updatedAt: worker.updatedAt
            };
          });
        }

        if (historyWorkerInstanceIds.length > 0) {
          const existingMembers = nextState.members || [];
          const memberMap = new Map(existingMembers.map(member => [member.instanceId, member]));
          historyWorkerInstanceIds.forEach(instanceId => {
            if (!memberMap.has(instanceId)) {
              memberMap.set(instanceId, {
                instanceId,
                agentName: deriveWorkerDisplayName(instanceId),
                status: 'IDLE',
                lifecycleStatus: 'STOPPED',
                loopRunning: false,
                createdAt: new Date().toISOString()
              });
            }
          });
          nextState.members = Array.from(memberMap.values());
        }

        set(current => {
          if (current.currentSession?.id !== sessionId || currentEpoch !== selectSessionEpoch) {
            return {};
          }

          const patch: Partial<TeamState> = { ...nextState };

          if (historyMessages) {
            patch.messages = mergeLeadMessages(historyMessages, current.messages);
          }

          if (historyWorkerMessages) {
            patch.workerMessages = mergeWorkerMessageMaps(historyWorkerMessages, current.workerMessages);
          }

          if (nextState.tasks) {
            const runtimeTaskMap = new Map(current.tasks.map(task => [task.taskId, task]));
            patch.tasks = sortTasksById(nextState.tasks.map(task => {
              const runtimeTask = runtimeTaskMap.get(task.taskId);
              if (!runtimeTask) {
                return task;
              }
              return {
                ...task,
                status: runtimeTask.status,
                owner: runtimeTask.owner ?? task.owner,
                description: runtimeTask.description || task.description,
                completionNote: runtimeTask.completionNote ?? task.completionNote
              };
            }));
          }

          return patch;
        });
      } catch (error) {
        console.warn('[Team] Failed to hydrate session snapshot:', error);
      }
    })();

    // 清除旧的事件 ID
    clearEventIdSet(sessionId);

    // 建立 SSE 连接
    const eventSourceUrl = apiUrl(`/v2/team/session/${sessionId}/events`);
    let reconnectAttempts = 0;

    const cleanupReconnect = () => {
      if (reconnectTimeoutId) {
        clearTimeout(reconnectTimeoutId);
        reconnectTimeoutId = null;
      }
    };

    const connectSSE = () => {
      const controller = new AbortController();
      currentAbortController = controller;
      const lastEventId = getLastEventId(sessionId);
      const headers = lastEventId ? { 'Last-Event-ID': lastEventId } : undefined;
      // Desktop App 没有浏览器标签页切换，但 session 切换/断网重连仍走同一条 SSE 恢复链路。

      fetchEventSource(eventSourceUrl, {
        signal: controller.signal,
        headers,

        async onopen(response) {
          if (response.ok) {
            console.log('[Team SSE] Connection established:', { sessionId, lastEventId: lastEventId || null });
            set({ isConnected: true });
            reconnectAttempts = 0;
          } else {
            throw new Error(`SSE connection failed with status ${response.status}`);
          }
        },

        onmessage(ev) {
          if (typeof ev.id === 'string' && ev.id.trim()) {
            saveLastEventId(sessionId, ev.id);
          }
          if (!ev.event || ev.event === 'message') {
            console.debug('[Team SSE] Received message event:', { sessionId, id: ev.id, event: ev.event });
          }
          handleSSEEvent(ev, sessionId, set, get);
        },

        onerror(err) {
          if (err.name === 'AbortError') {
            return;
          }

          console.error('[Team SSE] Connection error:', err);
          set({ isConnected: false });

          if (reconnectAttempts < RECONNECT_CONFIG.maxRetries) {
            const delay = getReconnectDelay(reconnectAttempts);
            reconnectAttempts++;

            console.log(`[Team SSE] Reconnecting in ${Math.round(delay)}ms (attempt ${reconnectAttempts}/${RECONNECT_CONFIG.maxRetries})`);

            reconnectTimeoutId = setTimeout(() => {
              connectSSE();
            }, delay);
          } else {
            console.error('[Team SSE] Max reconnection attempts reached');
            set({
              error: 'Connection lost. Please refresh the page.',
              leadLifecycleStatus: 'STOPPED',
              leadStatus: 'idle',
              isConnected: false
            });
          }
        },

        onclose() {
          console.log('[Team SSE] Connection closed');
          set({ isConnected: false });
          cleanupReconnect();

          if (controller.signal.aborted) {
            console.log('[Team SSE] Connection aborted intentionally');
            return;
          }

          if (reconnectAttempts < RECONNECT_CONFIG.maxRetries) {
            const delay = getReconnectDelay(reconnectAttempts);
            reconnectAttempts++;

            console.log(`[Team SSE] Reconnecting after close (attempt ${reconnectAttempts}/${RECONNECT_CONFIG.maxRetries})`);

            reconnectTimeoutId = setTimeout(() => {
              connectSSE();
            }, delay);
          }
        }
      }).catch(err => {
        if (err.name !== 'AbortError') {
          console.error('[Team SSE] Fatal error:', err);
          set({ error: err.message, leadLifecycleStatus: 'STOPPED', leadStatus: 'idle', isConnected: false });
        }
      });
    };

    // 启动 SSE 连接
    connectSSE();
    void get().syncRuntimeStatus();
    clearRuntimeStatusPolling();
    runtimeStatusPollTimer = setInterval(() => {
      void get().syncRuntimeStatus();
    }, 1500);
  },

  deleteSession: async (sessionId: string) => {
    try {
      await api.sessions.delete(sessionId);
      removeLastEventId(sessionId);

      set(state => {
        const newSessions = state.sessions.filter(s => s.id !== sessionId);
        const newCurrentSession = state.currentSession?.id === sessionId ? null : state.currentSession;

        // 如果删除的是当前 session，断开连接
        if (state.currentSession?.id === sessionId) {
          get().disconnectStream();
        }

        return {
          sessions: newSessions,
          currentSession: newCurrentSession
        };
      });
    } catch (error: unknown) {
      console.error('[Team] Failed to delete session:', error);
      set({ error: 'Failed to delete session' });
      throw error;
    }
  },

  sendToLead: async (message: string) => {
    const { currentSession, isTeamStarted } = get();
    if (!currentSession) {
      console.error('[Team] No active session');
      return;
    }

    const sessionId = currentSession.id;

    const optimisticUserMessage: LeadMessage = {
      id: `${Date.now()}_user_message`,
      type: 'user',
      content: message,
      timestamp: new Date().toISOString()
    };

    set(state => ({
      messages: [...state.messages, optimisticUserMessage]
    }));
    console.debug('[Team] Optimistic message appended:', {
      sessionId,
      isFirstMessage: !isTeamStarted
    });

    try {
      // 如果是第一条消息，先启动团队
      if (!isTeamStarted) {
        console.log('[Team] Starting team session with first message:', message);

        await api.team.startTeamSession(sessionId, message);
        set({ isTeamStarted: true });
        void get().syncRuntimeStatus();
        console.log('[Team] Team session started successfully');
      } else {
        // 后续消息直接发送
        console.log('[Team] Sending message to lead:', message);
        await api.team.sendMessageToLead(sessionId, message);
      }
    } catch (error: unknown) {
      console.error('[Team] Failed to send message:', error);
      const errMsg = error instanceof Error ? error.message : 'Failed to send message';
      set({
        error: errMsg,
        leadLifecycleStatus: 'STOPPED',
        leadStatus: 'idle',
        // 首条消息失败时回滚，避免进入“已启动但实际未启动”的假状态
        isTeamStarted: isTeamStarted ? true : false
      });

      set(state => ({
        messages: [
          ...state.messages,
          {
            id: `${Date.now()}_send_error`,
            type: 'system',
            content: `发送失败: ${errMsg}`,
            timestamp: new Date().toISOString()
          }
        ]
      }));
    }
  },

  stopTeam: async () => {
    const { currentSession } = get();
    if (!currentSession) {
      console.error('[Team] No active session');
      return;
    }

    const sessionId = currentSession.id;
    console.log('[Team] Stopping team session:', sessionId);
    set({ teamPhase: 'shutting_down', isTeamStarted: false });

    try {
      await api.team.stopTeamSession(sessionId);
      await get().syncRuntimeStatus();
      console.log('[Team] Team session stopped successfully');
    } catch (error: unknown) {
      console.error('[Team] Failed to stop team:', error);
      set({ error: 'Failed to stop team' });
    }

    get().disconnectStream();
  },

  disconnectStream: () => {
    clearRuntimeStatusPolling();

    if (reconnectTimeoutId) {
      clearTimeout(reconnectTimeoutId);
      reconnectTimeoutId = null;
    }

    if (currentAbortController) {
      try {
        currentAbortController.abort();
      } catch (e) {
        console.debug('[Team] Abort completed:', e instanceof Error ? e.message : e);
      } finally {
        currentAbortController = null;
      }
    }

    // 断开所有 Worker SSE 连接
    get().disconnectAllWorkerStreams();

    set({ isConnected: false });
  },

  connectWorkerStream: (instanceId: string) => {
    const { currentSession, workerConnections } = get();
    if (!currentSession) return;

    // 如果已连接，先断开
    if (workerConnections[instanceId]) {
      workerConnections[instanceId].abort();
    }

    const sessionId = currentSession.id;
    const controller = new AbortController();

    set(state => ({
      workerConnections: { ...state.workerConnections, [instanceId]: controller },
      workerMessages: { ...state.workerMessages, [instanceId]: state.workerMessages[instanceId] || [] }
    }));

    const url = apiUrl(`/v2/team/session/${sessionId}/worker/${instanceId}/events`);

    fetchEventSource(url, {
      signal: controller.signal,
      async onopen(response) {
        if (!response.ok) throw new Error(`Worker SSE failed: ${response.status}`);
        console.log('[Team] Worker SSE connected:', instanceId);
      },
      onmessage(ev) {
        const eventType = ev.event;
        let data: Record<string, unknown>;
        try {
          data = JSON.parse(ev.data);
        } catch { return; }

        let msg: WorkerMessage | null = null;
        let readInboxHint: WorkerMessage | null = null;

        switch (eventType) {
          // ===== 工具调用开始 =====
          case 'tool_call_start':
            {
              const toolName = typeof data.toolName === 'string' ? data.toolName : 'unknown';
              const toolArgs = typeof data.toolArgs === 'string' ? data.toolArgs : JSON.stringify(data.toolArgs || {});
              const toolCallId = typeof data.toolCallId === 'string' ? data.toolCallId : '';
              msg = {
                id: `${Date.now()}_tool_call_${toolCallId}`,
                type: 'tool_call',
                content: toolArgs,
                timestamp: new Date().toISOString(),
                toolName,
                toolCallId,
                serverName: typeof data.serverName === 'string' ? data.serverName : undefined,
                toolArgs,
                toolStatus: 'RUNNING'
              };
            }
            break;

          // ===== 工具调用完成 =====
          case 'tool_call_complete':
            {
              const toolName = typeof data.toolName === 'string' ? data.toolName : 'unknown';
              const toolResult = typeof data.toolResult === 'string' ? data.toolResult : JSON.stringify(data.toolResult || {});
              const status = typeof data.status === 'string' ? data.status : 'SUCCESS';
              const toolCallId = typeof data.toolCallId === 'string' ? data.toolCallId : '';
              const errorMessage = typeof data.errorMessage === 'string' ? data.errorMessage : '';

              const isReadInboxCall = toolName === 'read_inbox' || toolName.endsWith('read_inbox');
              if (isReadInboxCall && status !== 'FAILED') {
                const senders = parseInboxSenders(toolResult);
                readInboxHint = {
                  id: `worker_read_inbox_hint_${toolCallId || Date.now()}`,
                  type: 'system',
                  content: buildInboxReadHint('Worker', senders),
                  timestamp: new Date().toISOString()
                };
              }

              msg = {
                id: `${Date.now()}_tool_result_${toolCallId}`,
                type: 'tool_result',
                content: status === 'FAILED'
                  ? `❌ ${errorMessage || toolResult}`
                  : toolResult,
                timestamp: new Date().toISOString(),
                toolName,
                toolCallId,
                toolResult,
                toolStatus: status === 'FAILED' ? 'FAILED' : 'SUCCESS',
                errorMessage: errorMessage || undefined,
                durationMs: typeof data.durationMs === 'number' ? data.durationMs : undefined
              };
            }
            break;

          // ===== Worker 流式输出 =====
          case 'worker_output':
            {
              const output = typeof data.output === 'string' ? data.output : '';
              if (output) {
                msg = {
                  id: `${Date.now()}_output_stream`,
                  type: 'output',
                  content: output,
                  timestamp: new Date().toISOString()
                };
              }
            }
            break;

          // ===== 以下保留原来的，以备将来使用 =====
          case 'step_start':
            msg = {
              id: `${Date.now()}_thinking`,
              type: 'thinking',
              content: typeof data.content === 'string' ? data.content : 'Worker 开始思考...',
              timestamp: new Date().toISOString()
            };
            break;

          case 'step_completed':
            msg = {
              id: `${Date.now()}_output`,
              type: 'output',
              content: typeof data.content === 'string' ? data.content : 'Worker 完成步骤',
              timestamp: new Date().toISOString()
            };
            break;

          case 'worker_report':
            msg = {
              id: `${Date.now()}_output`,
              type: 'output',
              content: typeof data.content === 'string' ? data.content : '',
              timestamp: new Date().toISOString()
            };
            break;

          case 'error':
            msg = {
              id: `${Date.now()}_error`,
              type: 'error',
              content: typeof data.error === 'string' ? data.error : JSON.stringify(data),
              timestamp: new Date().toISOString()
            };
            break;

          default:
            console.warn('[Team Worker] Unknown event type:', eventType, data);
            break;
        }

        if (msg) {
          set(state => ({
            workerMessages: (() => {
              const existingMessages = state.workerMessages[instanceId] || [];
              const appendReadInboxHint = (list: WorkerMessage[]): WorkerMessage[] => {
                if (!readInboxHint || list.some(item => item.id === readInboxHint.id)) {
                  return list;
                }
                return [...list, readInboxHint];
              };

              if (eventType === 'worker_output' && msg.type === 'output') {
                const lastMessage = existingMessages[existingMessages.length - 1];

                if (
                  lastMessage &&
                  lastMessage.type === 'output' &&
                  lastMessage.id.endsWith('_output_stream')
                ) {
                  const updatedLastMessage: WorkerMessage = {
                    ...lastMessage,
                    content: lastMessage.content + msg.content,
                    timestamp: msg.timestamp
                  };

                  return {
                    ...state.workerMessages,
                    [instanceId]: appendReadInboxHint([
                      ...existingMessages.slice(0, -1),
                      updatedLastMessage
                    ])
                  };
                }
              }

              if ((eventType === 'tool_call_start' || eventType === 'tool_call_complete') && msg.toolCallId) {
                const existingToolIndex = existingMessages.findIndex(
                  m => m.toolCallId === msg!.toolCallId && (m.type === 'tool_call' || m.type === 'tool_result')
                );

                if (existingToolIndex >= 0) {
                  const existingToolMessage = existingMessages[existingToolIndex];
                  const mergedToolMessage: WorkerMessage = {
                    ...existingToolMessage,
                    ...msg,
                    // 保留 start 阶段的参数信息，避免 complete 事件里缺少 args 时丢失
                    toolArgs: msg.toolArgs ?? existingToolMessage.toolArgs,
                    // 合并展示内容优先级：失败错误 > 结果 > 之前内容
                    content: msg.content || existingToolMessage.content
                  };

                  return {
                    ...state.workerMessages,
                    [instanceId]: appendReadInboxHint([
                      ...existingMessages.slice(0, existingToolIndex),
                      mergedToolMessage,
                      ...existingMessages.slice(existingToolIndex + 1)
                    ])
                  };
                }
              }

              return {
                ...state.workerMessages,
                [instanceId]: appendReadInboxHint([...existingMessages, msg])
              };
            })()
          }));
        }
      },
      onerror(err) {
        if (err.name === 'AbortError') return;
        console.error('[Team] Worker SSE error:', instanceId, err);
      },
      onclose() {
        console.log('[Team] Worker SSE closed:', instanceId);
      }
    }).catch(() => {});
  },

  disconnectWorkerStream: (instanceId: string) => {
    const controller = get().workerConnections[instanceId];
    if (controller) {
      controller.abort();
      set(state => {
        const nextConnections = { ...state.workerConnections };
        delete nextConnections[instanceId];
        return { workerConnections: nextConnections };
      });
    }
  },

  disconnectAllWorkerStreams: () => {
    const { workerConnections } = get();
    Object.keys(workerConnections).forEach(instanceId => {
      get().disconnectWorkerStream(instanceId);
    });
  },

  sendToWorker: async (instanceId: string, message: string) => {
    const { currentSession } = get();
    if (!currentSession) {
      console.error('[Team] No active session');
      return;
    }

    const sessionId = currentSession.id;

    // 乐观更新：立即显示用户消息
    const userMsg: WorkerMessage = {
      id: `${Date.now()}_user`,
      type: 'user',
      content: message,
      timestamp: new Date().toISOString()
    };

    set(state => ({
      workerMessages: {
        ...state.workerMessages,
        [instanceId]: [...(state.workerMessages[instanceId] || []), userMsg]
      }
    }));

    try {
      await api.team.sendMessageToWorker(sessionId, instanceId, message);
      console.log('[Team] Message sent to worker:', instanceId);
    } catch (error: unknown) {
      console.error('[Team] Failed to send message to worker:', error);

      // 添加错误消息
      const errorMsg: WorkerMessage = {
        id: `${Date.now()}_error`,
        type: 'error',
        content: '发送消息失败',
        timestamp: new Date().toISOString()
      };

      set(state => ({
        workerMessages: {
          ...state.workerMessages,
          [instanceId]: [...(state.workerMessages[instanceId] || []), errorMsg]
        }
      }));
    }
  },

  syncRuntimeStatus: async () => {
    const sessionId = get().currentSession?.id;
    if (!sessionId) {
      return;
    }

    try {
      const runtimeStatus = await api.team.getRuntimeStatus(sessionId);
      if (get().currentSession?.id !== sessionId) {
        return;
      }

      const runtimeMembers: TeamMember[] = (runtimeStatus.workers || []).map((worker: WorkerInstance) => {
        const status = normalizeWorkerStatus(worker.status);
        const loopRunning = Boolean(worker.loopRunning);
        const lifecycleStatus = mapWorkerLifecycleStatus(status, loopRunning, worker.lifecycleStatus);

        return {
          instanceId: worker.instanceId,
          agentName: worker.agentName || deriveWorkerDisplayName(worker.instanceId),
          status,
          lifecycleStatus,
          loopRunning,
          currentTask: typeof worker.currentTaskId === 'number' && worker.currentTaskId > 0
            ? String(worker.currentTaskId)
            : undefined,
          createdAt: worker.startedAt,
          updatedAt: worker.updatedAt
        };
      });

      set(state => {
        if (state.currentSession?.id !== sessionId) {
          return {};
        }

        const byId = new Map<string, TeamMember>();
        state.members.forEach(member => byId.set(member.instanceId, member));

        runtimeMembers.forEach(member => {
          const previous = byId.get(member.instanceId);
          byId.set(member.instanceId, {
            ...previous,
            ...member,
            agentName: member.agentName || previous?.agentName || deriveWorkerDisplayName(member.instanceId),
            createdAt: member.createdAt || previous?.createdAt,
            updatedAt: member.updatedAt || previous?.updatedAt,
            currentTask: member.currentTask ?? previous?.currentTask
          });
        });

        state.members.forEach(member => {
          const stillExists = runtimeMembers.some(runtimeMember => runtimeMember.instanceId === member.instanceId);
          if (stillExists) {
            return;
          }
          byId.set(member.instanceId, {
            ...member,
            status: 'SHUTDOWN',
            lifecycleStatus: 'DESTROYED',
            loopRunning: false,
            updatedAt: new Date().toISOString()
          });
        });

        const mergedMembers = [...byId.values()].sort((a, b) => {
          const aIndex = parseWorkerIndexFromInstanceId(a.instanceId) ?? Number.MAX_SAFE_INTEGER;
          const bIndex = parseWorkerIndexFromInstanceId(b.instanceId) ?? Number.MAX_SAFE_INTEGER;
          if (aIndex !== bIndex) {
            return aIndex - bIndex;
          }
          return a.instanceId.localeCompare(b.instanceId);
        });

        const leadLifecycleStatus = normalizeLifecycleStatus(runtimeStatus.leadLifecycleStatus)
          || state.leadLifecycleStatus
          || 'STOPPED';

        return {
          leadLifecycleStatus,
          leadStatus: toLeadStatus(leadLifecycleStatus),
          members: mergedMembers
        };
      });
    } catch (error) {
      console.warn('[Team] Failed to sync runtime status:', error);
    }
  },

  setActiveWorker: (instanceId: string | null) => {
    set({ activeWorkerId: instanceId });
  }
}));
