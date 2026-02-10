import { create } from 'zustand';
import { api } from '../../../shared/api';
import type { Session, A2AMessage, ToolCallDTO } from '../../../shared/api';
import { fetchEventSource } from '@microsoft/fetch-event-source';

interface SingleChatState {
  sessions: Session[];
  currentSession: Session | null;
  messages: A2AMessage[];
  toolCalls: ToolCallDTO[];
  isStreaming: boolean;
  error: string | null;

  fetchSessions: () => Promise<void>;
  selectSession: (session: Session) => Promise<void>;
  clearSession: () => void;
  deleteSession: (sessionId: string) => Promise<void>;
  sendMessage: (sessionId: string, userMessage: string) => void;
  disconnectStream: () => void;
}

// 存储当前的 AbortController，用于关闭 SSE 连接
let currentAbortController: AbortController | null = null;

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

      const chatMessages: A2AMessage[] = dbMessages.map(msg => {
        const isUser = msg.role === 'user';
        return {
          agentId: isUser ? 'human' : (msg.agentId?.toString() || 'assistant'),
          agentName: msg.agentName || (isUser ? 'User' : 'Agent'),
          content: msg.content,
          timestamp: msg.createdAt,
          type: 'text'
        };
      });

      // 加载工具调用记录
      const toolCallRecords = await api.toolCalls.getBySession(session.id);

      set({ messages: chatMessages, toolCalls: toolCallRecords });
    } catch (error: unknown) {
      console.warn('Failed to fetch messages:', error);
      set({ messages: [], toolCalls: [] });
    }
  },

  clearSession: () => {
    get().disconnectStream();
    set({ currentSession: null, messages: [], isStreaming: false });
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
      const eventSourceUrl = `/api/sessions/${sessionId}/single-chat`;

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
                agentId: 'assistant',
                agentName: 'Agent',
                content: '',
                timestamp: new Date().toISOString(),
                type: 'text'
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
              // 追加增量内容到最后一条消息
              const delta = data.delta || '';
              set(state => {
                const messages = [...state.messages];
                const lastMsg = messages[messages.length - 1];
                if (lastMsg && lastMsg.agentId === 'assistant') {
                  messages[messages.length - 1] = {
                    ...lastMsg,
                    content: lastMsg.content + delta
                  };
                }
                return { messages };
              });
            } else if (eventType === 'chat_complete') {
              console.log('[SingleChat] Chat completed');
              set({ isStreaming: false });
              disconnectStream();
            } else if (eventType === 'chat_error') {
              console.error('[SingleChat] Error:', data.error);
              set({ error: data.error, isStreaming: false });
              disconnectStream();
            } else if (eventType === 'tool_call_start' || eventType === 'tool_call_complete' || eventType === 'tool_call_failed') {
              // 工具调用事件：更新或添加工具调用记录
              const toolCall = data as ToolCallDTO;
              set(state => {
                const toolCalls = [...state.toolCalls];
                const existingIndex = toolCalls.findIndex(tc => tc.toolCallId === toolCall.toolCallId);

                if (existingIndex >= 0) {
                  // 更新现有记录
                  toolCalls[existingIndex] = toolCall;
                } else {
                  // 添加新记录
                  toolCalls.push(toolCall);
                }

                return { toolCalls };
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
