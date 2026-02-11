import { create } from 'zustand';
import { api } from '../../../shared/api';
import { fetchEventSource } from '@microsoft/fetch-event-source';
import type { Session } from '../../../shared/api/types';

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
  status: 'PENDING' | 'ASSIGNED' | 'WORKING' | 'COMPLETED' | 'FAILED';
  owner?: string;
}

export interface WorkerMessage {
  id: string;
  type: 'thinking' | 'tool_call' | 'tool_result' | 'output' | 'system' | 'user' | 'error';
  content: string;
  timestamp: string;
  toolName?: string;
}

export interface LeadMessage {
  id: string;
  type: 'system' | 'lead' | 'worker' | 'user';
  content: string;
  timestamp: string;
  agentName?: string;
  workerId?: string;
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
  const eventType = ev.event;
  let eventData: unknown;

  try {
    eventData = JSON.parse(ev.data);
  } catch (e) {
    console.error('[Team] Failed to parse event data:', e);
    return;
  }

  const data = (eventData && typeof eventData === 'object') ? (eventData as Record<string, unknown>) : {};

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

        const newTask: TaskItem = {
          taskId,
          subject,
          description,
          status: 'PENDING',
        };

        const newMessage: LeadMessage = {
          id: `${Date.now()}_task_created`,
          type: 'system',
          content: `任务创建: #${taskId} ${subject}`,
          timestamp: new Date().toISOString()
        };

        set({
          tasks: [...tasks, newTask],
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

        set({
          tasks: tasks.map(t =>
            t.taskId === taskId ? { ...t, status: 'COMPLETED' as const } : t
          )
        });
      }
      break;

    case 'task_unblocked':
      {
        const taskId = typeof data.taskId === 'number' ? data.taskId : 0;

        set({
          tasks: tasks.map(t =>
            t.taskId === taskId ? { ...t, status: 'PENDING' as const } : t
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
          messages: [...messages, newMessage]
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
      const allSessions = await api.sessions.list();
      // 只显示 GROUP 类型的 session（Team Studio 专用）
      const teamSessions = allSessions.filter(s => s.type === 'GROUP');
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
        type: 'GROUP',
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
      error: null,
      isConnected: false,
      isTeamStarted: false,
      teamId: null
    });

    // 清除旧的事件 ID
    clearEventIdSet(sessionId);

    // 建立 SSE 连接
    const eventSourceUrl = `/api/v2/team/session/${sessionId}/events`;
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
      set({ error: 'Failed to send message', leadStatus: 'idle' });
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
    set({ teamPhase: 'shutting_down', leadStatus: 'shutdown' });

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

    const url = `/api/v2/team/session/${sessionId}/worker/${instanceId}/events`;

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
          case 'step_start':
            msg = {
              id: `${Date.now()}_thinking`,
              type: 'thinking',
              content: typeof data.content === 'string' ? data.content : 'Worker 开始思考...',
              timestamp: new Date().toISOString()
            };
            break;

          case 'tool_call':
            {
              const toolName = typeof data.toolName === 'string' ? data.toolName : 'unknown';
              const input = typeof data.input === 'string' ? data.input : JSON.stringify(data.input || {});
              msg = {
                id: `${Date.now()}_tool_call`,
                type: 'tool_call',
                content: input,
                timestamp: new Date().toISOString(),
                toolName
              };
            }
            break;

          case 'tool_result':
            {
              const toolName = typeof data.toolName === 'string' ? data.toolName : 'unknown';
              const output = typeof data.output === 'string' ? data.output : JSON.stringify(data.output || {});
              msg = {
                id: `${Date.now()}_tool_result`,
                type: 'tool_result',
                content: output,
                timestamp: new Date().toISOString(),
                toolName
              };
            }
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
            msg = {
              id: `${Date.now()}_system`,
              type: 'system',
              content: JSON.stringify(data),
              timestamp: new Date().toISOString()
            };
        }

        if (msg) {
          set(state => ({
            workerMessages: {
              ...state.workerMessages,
              [instanceId]: [...(state.workerMessages[instanceId] || []), msg!]
            }
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
        const { [instanceId]: _, ...rest } = state.workerConnections;
        return { workerConnections: rest };
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
