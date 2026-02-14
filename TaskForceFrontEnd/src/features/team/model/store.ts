import { create } from 'zustand';
import { api } from '../../../shared/api';
import { fetchEventSource } from '@microsoft/fetch-event-source';
import type { Session } from '../../../shared/api/types';
import { apiUrl } from '../../../shared/api/base';

// ========== 类型定义 ==========

export interface TeamMember {
  instanceId: string;
  agentName: string;
  status: 'IDLE' | 'BUSY' | 'ERROR' | 'STOPPED';
  currentTask?: string;
  createdAt: string;
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
}

// ========== SSE 连接管理 ==========

let currentAbortController: AbortController | null = null;
let reconnectTimeoutId: ReturnType<typeof setTimeout> | null = null;

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
          content: `团队已启动 (Team ID: ${teamId})`,
          timestamp: new Date().toISOString()
        };
        set({
          teamId,
          teamPhase: 'active',
          leadStatus: 'active',
          messages: [...messages, newMessage]
        });
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
      }
      break;

    case 'worker_report':
      {
        const workerId = typeof data.workerId === 'string' ? data.workerId : '';
        const agentName = typeof data.agentName === 'string' ? data.agentName : 'Worker';
        const content = typeof data.content === 'string' ? data.content : '';
        const status = typeof data.status === 'string' ? data.status as TeamMember['status'] : undefined;

        const newMessage: LeadMessage = {
          id: `${Date.now()}_worker_report`,
          type: 'worker',
          content,
          timestamp: new Date().toISOString(),
          agentName,
          workerId
        };

        // 更新 worker 状态
        const updatedMembers = status && workerId
          ? members.map(m => m.instanceId === workerId ? { ...m, status } : m)
          : members;

        set({
          members: updatedMembers,
          messages: [...messages, newMessage]
        });
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
          if (toolCallId) {
            const existingIndex = state.messages.findIndex(
              msg => msg.toolCallId === toolCallId && (msg.type === 'tool_call' || msg.type === 'tool_result')
            );
            if (existingIndex >= 0) {
              const existing = state.messages[existingIndex];
              const merged: LeadMessage = {
                ...existing,
                ...newMessage,
                toolArgs: newMessage.toolArgs ?? existing.toolArgs
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
          leadStatus: 'shutdown',
          isTeamStarted: false,
          messages: [...messages, newMessage]
        });
      }
      break;

    case 'session_complete':
      {
        set({
          teamPhase: 'closed',
          leadStatus: 'idle',
          isTeamStarted: false,
          members: members.map(member => ({ ...member, status: 'STOPPED' }))
        });
      }
      break;

    case 'error':
      {
        const errorMsg = typeof data.error === 'string' ? data.error : 'Unknown error';
        set({
          error: errorMsg,
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
    console.log('[Team] Selecting session:', session.id);

    // 断开旧连接
    get().disconnectStream();

    const sessionId = session.id;

    set({
      currentSession: session,
      teamPhase: 'not_started',
      leadStatus: 'idle',
      messages: [],
      members: [],
      tasks: [],
      error: null,
      isConnected: false,
      isTeamStarted: false,
      teamId: null
    });

    void (async () => {
      try {
        const [taskBoardResult, workersResult] = await Promise.allSettled([
          api.team.getTaskBoard(sessionId),
          api.team.getWorkers(sessionId)
        ]);

        const state = get();
        if (state.currentSession?.id !== sessionId) {
          return;
        }

        const nextState: Partial<TeamState> = {};

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
          nextState.members = workersResult.value.map(worker => ({
            instanceId: worker.instanceId,
            agentName: worker.agentName,
            status: worker.status,
            currentTask: worker.currentTask,
            createdAt: worker.createdAt
          }));
        }

        set(nextState);
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

      fetchEventSource(eventSourceUrl, {
        signal: controller.signal,

        async onopen(response) {
          if (response.ok) {
            console.log('[Team SSE] Connection established');
            set({ isConnected: true });
            reconnectAttempts = 0;
          } else {
            throw new Error(`SSE connection failed with status ${response.status}`);
          }
        },

        onmessage(ev) {
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
          set({ error: err.message, leadStatus: 'idle', isConnected: false });
        }
      });
    };

    // 启动 SSE 连接
    connectSSE();
  },

  deleteSession: async (sessionId: string) => {
    try {
      await api.sessions.delete(sessionId);

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

    try {
      // 如果是第一条消息，先启动团队
      if (!isTeamStarted) {
        console.log('[Team] Starting team session with first message:', message);
        set({ leadStatus: 'active', isTeamStarted: true });

        await api.team.startTeamSession(sessionId, message);
        console.log('[Team] Team session started successfully');
      } else {
        // 后续消息直接发送
        console.log('[Team] Sending message to lead:', message);
        await api.team.sendMessageToLead(sessionId, message);
      }

      // 乐观更新：立即显示用户消息
      const newMessage: LeadMessage = {
        id: `${Date.now()}_user_message`,
        type: 'user',
        content: message,
        timestamp: new Date().toISOString()
      };

      set(state => ({
        messages: [...state.messages, newMessage]
      }));
    } catch (error: unknown) {
      console.error('[Team] Failed to send message:', error);
      const errMsg = error instanceof Error ? error.message : 'Failed to send message';
      set({
        error: errMsg,
        leadStatus: 'idle',
        // 首条消息失败时回滚，避免进入“已启动但实际未启动”的假状态
        isTeamStarted: isTeamStarted ? true : false
      });
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
    set({ teamPhase: 'shutting_down', leadStatus: 'shutdown', isTeamStarted: false });

    try {
      await api.team.stopTeamSession(sessionId);
      console.log('[Team] Team session stopped successfully');
    } catch (error: unknown) {
      console.error('[Team] Failed to stop team:', error);
      set({ error: 'Failed to stop team' });
    }

    get().disconnectStream();
  },

  disconnectStream: () => {
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
                    [instanceId]: [
                      ...existingMessages.slice(0, -1),
                      updatedLastMessage
                    ]
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
                    [instanceId]: [
                      ...existingMessages.slice(0, existingToolIndex),
                      mergedToolMessage,
                      ...existingMessages.slice(existingToolIndex + 1)
                    ]
                  };
                }
              }

              return {
                ...state.workerMessages,
                [instanceId]: [...existingMessages, msg]
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

  setActiveWorker: (instanceId: string | null) => {
    set({ activeWorkerId: instanceId });
  }
}));
