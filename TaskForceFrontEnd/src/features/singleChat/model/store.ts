import { create } from 'zustand';
import { api } from '../../../shared/api';
import type { Session, ToolCallDTO } from '../../../shared/api';
import { fetchEventSource } from '@microsoft/fetch-event-source';
import { apiUrl } from '../../../shared/api/base';

interface SingleChatMessage {
  id: string;
  agentId: string;
  agentName: string;
  content: string;
  timestamp: string;
  type: 'text' | 'tool_call';
  streamState?: 'streaming' | 'completed';
  toolCall?: ToolCallDTO;
}

interface SingleChatState {
  sessions: Session[];
  currentSession: Session | null;
  messages: SingleChatMessage[];
  toolCalls: ToolCallDTO[];
  isStreaming: boolean;
  error: string | null;

  fetchSessions: () => Promise<void>;
  selectSession: (session: Session) => Promise<void>;
  clearSession: () => void;
  deleteSession: (sessionId: string) => Promise<void>;
  sendMessage: (sessionId: string, userMessage: string) => void;
  stopStreaming: (sessionId: string) => Promise<void>;
  disconnectStream: () => void;
}

// 存储当前的 AbortController，用于关闭 SSE 连接
let currentAbortController: AbortController | null = null;

function toTimeValue(timestamp?: string): number {
  if (!timestamp) {
    return Number.MAX_SAFE_INTEGER;
  }
  const time = new Date(timestamp).getTime();
  return Number.isNaN(time) ? Number.MAX_SAFE_INTEGER : time;
}

function resolveToolTimestamp(toolCall: ToolCallDTO): string {
  return toolCall.startedAt
    || toolCall.completedAt
    || new Date().toISOString();
}

function toToolMessage(toolCall: ToolCallDTO, existing?: SingleChatMessage): SingleChatMessage {
  return {
    id: `tool_${toolCall.toolCallId}`,
    agentId: 'assistant',
    agentName: 'Agent',
    content: '',
    timestamp: existing?.timestamp || resolveToolTimestamp(toolCall),
    type: 'tool_call',
    toolCall
  };
}

function markStreamingAssistantCompleted(messages: SingleChatMessage[]): SingleChatMessage[] {
  for (let i = messages.length - 1; i >= 0; i--) {
    const msg = messages[i];
    if (msg.type === 'text' && msg.agentId === 'assistant' && msg.streamState === 'streaming') {
      const next = [...messages];
      next[i] = { ...msg, streamState: 'completed' };
      return next;
    }
  }
  return messages;
}

function finalizeStreamingAssistantForTool(messages: SingleChatMessage[]): SingleChatMessage[] {
  for (let i = messages.length - 1; i >= 0; i--) {
    const msg = messages[i];
    if (msg.type === 'text' && msg.agentId === 'assistant' && msg.streamState === 'streaming') {
      const next = [...messages];
      // 工具事件在首个 delta 前到达时，移除空占位，避免出现空白 assistant 行。
      if (!msg.content || !msg.content.trim()) {
        next.splice(i, 1);
      } else {
        next[i] = { ...msg, streamState: 'completed' };
      }
      return next;
    }
  }
  return messages;
}

function appendAssistantDelta(messages: SingleChatMessage[], delta: string): SingleChatMessage[] {
  if (!delta) {
    return messages;
  }
  for (let i = messages.length - 1; i >= 0; i--) {
    const msg = messages[i];
    if (msg.type === 'text' && msg.agentId === 'assistant' && msg.streamState === 'streaming') {
      const next = [...messages];
      next[i] = {
        ...msg,
        content: msg.content + delta
      };
      return next;
    }
  }
  return [
    ...messages,
    {
      id: `assistant_${Date.now()}`,
      agentId: 'assistant',
      agentName: 'Agent',
      content: delta,
      timestamp: new Date().toISOString(),
      type: 'text',
      streamState: 'streaming'
    }
  ];
}

function upsertToolMessage(messages: SingleChatMessage[], toolCall: ToolCallDTO): SingleChatMessage[] {
  const index = messages.findIndex(
    msg => msg.type === 'tool_call' && msg.toolCall?.toolCallId === toolCall.toolCallId
  );
  if (index < 0) {
    return [...messages, toToolMessage(toolCall)];
  }
  const next = [...messages];
  next[index] = toToolMessage(toolCall, next[index]);
  return next;
}

function parseToolStatus(
  eventType: string,
  rawStatus: unknown,
  previousStatus?: ToolCallDTO['status']
): ToolCallDTO['status'] {
  if (rawStatus === 'RUNNING' || rawStatus === 'SUCCESS' || rawStatus === 'FAILED') {
    return rawStatus;
  }
  if (eventType === 'tool_call_start') {
    return 'RUNNING';
  }
  if (eventType === 'tool_call_failed') {
    return 'FAILED';
  }
  if (eventType === 'tool_call_complete') {
    return previousStatus === 'FAILED' ? 'FAILED' : 'SUCCESS';
  }
  return previousStatus || 'RUNNING';
}

function mergeToolCallRecord(
  eventType: string,
  incoming: unknown,
  previous?: ToolCallDTO
): ToolCallDTO | null {
  if (!incoming || typeof incoming !== 'object') {
    return null;
  }

  const data = incoming as Partial<ToolCallDTO> & Record<string, unknown>;
  const toolCallId = typeof data.toolCallId === 'string' && data.toolCallId.trim()
    ? data.toolCallId
    : previous?.toolCallId;

  if (!toolCallId) {
    return null;
  }

  const toolName = typeof data.toolName === 'string' && data.toolName.trim()
    ? data.toolName
    : (previous?.toolName || 'unknown');

  return {
    toolCallId,
    toolName,
    serverName: typeof data.serverName === 'string' ? data.serverName : previous?.serverName,
    instanceId: typeof data.instanceId === 'string' ? data.instanceId : previous?.instanceId,
    // toolArgs 在 complete 事件通常缺失，必须保留 start 事件的值
    toolArgs: typeof data.toolArgs === 'string' ? data.toolArgs : (previous?.toolArgs || ''),
    toolResult: typeof data.toolResult === 'string' ? data.toolResult : previous?.toolResult,
    status: parseToolStatus(eventType, data.status, previous?.status),
    errorMessage: typeof data.errorMessage === 'string' ? data.errorMessage : previous?.errorMessage,
    durationMs: typeof data.durationMs === 'number' ? data.durationMs : previous?.durationMs,
    stepId: typeof data.stepId === 'string' ? data.stepId : previous?.stepId,
    sequence: typeof data.sequence === 'number'
      ? data.sequence
      : (typeof previous?.sequence === 'number' ? previous.sequence : 0),
    startedAt: typeof data.startedAt === 'string' ? data.startedAt : previous?.startedAt,
    completedAt: typeof data.completedAt === 'string' ? data.completedAt : previous?.completedAt
  };
}

export const useSingleChatStore = create<SingleChatState>((set, get) => ({
  sessions: [],
  currentSession: null,
  messages: [],
  toolCalls: [],
  isStreaming: false,
  error: null,

  fetchSessions: async () => {
    try {
      const sessions = await api.sessions.listByType('CHAT');
      set({ sessions });
    } catch (error: unknown) {
      console.warn("Failed to fetch single-chat sessions:", error);
      set({ sessions: [], error: error instanceof Error ? error.message : String(error) });
    }
  },

  selectSession: async (session) => {
    get().disconnectStream();
    set({ currentSession: session, messages: [], toolCalls: [], isStreaming: false, error: null });

    try {
      // 加载历史消息
      const dbMessages = await api.messages.getBySession(session.id);

      const chatMessages: SingleChatMessage[] = dbMessages.map(msg => {
        const isUser = msg.role === 'user';
        return {
          id: `msg_${msg.id}`,
          agentId: isUser ? 'human' : (msg.agentId?.toString() || 'assistant'),
          agentName: msg.agentName || (isUser ? 'User' : 'Agent'),
          content: msg.content,
          timestamp: msg.createdAt,
          type: 'text'
        };
      });

      // 加载工具调用记录
      const toolCallRecords = await api.toolCalls.getBySession(session.id);
      const toolMessages: SingleChatMessage[] = toolCallRecords.map(tc => toToolMessage(tc));
      const mergedTimeline = [...chatMessages, ...toolMessages]
        .sort((a, b) => toTimeValue(a.timestamp) - toTimeValue(b.timestamp));

      set({ messages: mergedTimeline, toolCalls: toolCallRecords });
    } catch (error: unknown) {
      console.warn('Failed to fetch messages:', error);
      set({ messages: [], toolCalls: [] });
    }
  },

  clearSession: () => {
    get().disconnectStream();
    set({ currentSession: null, messages: [], toolCalls: [], isStreaming: false });
  },

  deleteSession: async (sessionId: string) => {
    try {
      await api.sessions.delete(sessionId);
      const currentSession = get().currentSession;

      if (currentSession?.id === sessionId) {
        get().clearSession();
      }

      set({ sessions: get().sessions.filter(s => s.id !== sessionId) });
    } catch (error: unknown) {
      console.error('Failed to delete session:', error);
      throw error;
    }
  },

  sendMessage: async (sessionId, userMessage) => {
    console.log('[SingleChat] Sending message:', { sessionId, userMessage });

    const { disconnectStream } = get();
    disconnectStream();

    set({ isStreaming: true, error: null });

    // 乐观更新：立即显示用户消息
    if (userMessage && userMessage.trim()) {
      set(state => ({
        messages: [...state.messages, {
          id: `user_${Date.now()}`,
          agentId: 'human',
          agentName: 'Human',
          content: userMessage,
          timestamp: new Date().toISOString(),
          type: 'text'
        }]
      }));
    }

    try {
      const controller = new AbortController();
      currentAbortController = controller;

      // 建立 SSE 连接
      const eventSourceUrl = apiUrl(`/sessions/${sessionId}/single-chat`);

      fetchEventSource(eventSourceUrl, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ message: userMessage }),
        signal: controller.signal,

        async onopen(response) {
          if (response.ok) {
            console.log('[SingleChat SSE] ✅ Connection established');
            // 添加一条空的 assistant 消息用于接收流式输出
            set(state => ({
              messages: [...state.messages, {
                id: `assistant_${Date.now()}`,
                agentId: 'assistant',
                agentName: 'Agent',
                content: '',
                timestamp: new Date().toISOString(),
                type: 'text',
                streamState: 'streaming'
              }]
            }));
          } else {
            throw new Error(`SSE connection failed with status ${response.status}`);
          }
        },

        onmessage(ev) {
          const eventType = ev.event;

          try {
            const data = JSON.parse(ev.data);

            if (eventType === 'chat_delta') {
              const delta = data.delta || '';
              set(state => {
                return { messages: appendAssistantDelta(state.messages, delta) };
              });
            } else if (eventType === 'chat_complete') {
              console.log('[SingleChat] Chat completed');
              set(state => ({
                isStreaming: false,
                messages: markStreamingAssistantCompleted(state.messages)
              }));
              disconnectStream();
            } else if (eventType === 'chat_error') {
              console.error('[SingleChat] Error:', data.error);
              set(state => ({
                error: data.error,
                isStreaming: false,
                messages: markStreamingAssistantCompleted(state.messages)
              }));
              disconnectStream();
            } else if (eventType === 'tool_call_start' || eventType === 'tool_call_complete' || eventType === 'tool_call_failed') {
              set(state => {
                const toolCalls = [...state.toolCalls];
                const incoming = data as Partial<ToolCallDTO>;
                const incomingId = typeof incoming.toolCallId === 'string' ? incoming.toolCallId : '';
                const existingIndex = incomingId
                  ? toolCalls.findIndex(tc => tc.toolCallId === incomingId)
                  : -1;

                const existingRecord = existingIndex >= 0 ? toolCalls[existingIndex] : undefined;
                const mergedRecord = mergeToolCallRecord(eventType, data, existingRecord);
                if (!mergedRecord) {
                  return {};
                }

                if (existingIndex >= 0) {
                  // 合并更新，保留已有入参等字段
                  toolCalls[existingIndex] = mergedRecord;
                } else {
                  toolCalls.push(mergedRecord);
                }

                const timelineMessages = finalizeStreamingAssistantForTool(state.messages);

                return {
                  toolCalls,
                  messages: upsertToolMessage(timelineMessages, mergedRecord)
                };
              });
            }
          } catch (e) {
            console.error('[SingleChat] Failed to parse event data:', e);
          }
        },

        onerror(err) {
          if (err.name === 'AbortError') {
            return;
          }
          console.error('[SingleChat SSE] Connection error:', err);
          set({ error: 'Connection error', isStreaming: false });
        },

        onclose() {
          console.log('[SingleChat SSE] Connection closed');
          set({ isStreaming: false });
        }
      }).catch(err => {
        if (err.name !== 'AbortError') {
          console.error('[SingleChat SSE] Fatal error:', err);
          set({ error: err.message, isStreaming: false });
        }
      });

    } catch (error: unknown) {
      console.error('[SingleChat] Failed to send message:', error);
      set({ error: 'Failed to send message', isStreaming: false });
    }
  },

  stopStreaming: async (sessionId: string) => {
    try {
      await api.sessions.stop(sessionId);
    } catch (error: unknown) {
      console.warn('[SingleChat] Failed to stop session on backend:', error);
    } finally {
      get().disconnectStream();
      set(state => ({
        isStreaming: false,
        messages: markStreamingAssistantCompleted(state.messages)
      }));
    }
  },

  disconnectStream: () => {
    if (currentAbortController) {
      try {
        currentAbortController.abort();
      } catch (e) {
        console.debug('[SingleChat] Abort completed:', e instanceof Error ? e.message : e);
      } finally {
        currentAbortController = null;
      }
    }
  }
}));
