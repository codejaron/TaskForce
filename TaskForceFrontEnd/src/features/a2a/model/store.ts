import { create } from 'zustand';
import { api } from '../../../shared/api';
import type { Session, A2AMessage } from '../../../shared/api';
import { fetchEventSource } from '@microsoft/fetch-event-source';

interface A2AState {
  sessions: Session[];
  currentSession: Session | null;
  messages: A2AMessage[];
  isLoading: boolean;
  error: string | null;

  fetchSessions: () => Promise<void>;
  selectSession: (session: Session) => Promise<void>;
  clearSession: () => void;

  // V2 Group Chat (Async workflow-based orchestration)
  startGroupChatV2: (sessionId: string, userMessage: string | null) => void;
  disconnectStream: () => void;
  stopStream: () => Promise<void>;
}

// 存储当前的 AbortController，用于关闭 SSE 连接
let currentAbortController: AbortController | null = null;

/**
 * 解析 Moderator 消息中的特殊标记
 * 提取黑板 JSON 和 WAIT_USER 标记
 */
function parseModeratorMessage(text: string): { cleanText: string; blackboard: any | null; waitUser: boolean } {
  const beginIdx = text.indexOf('BLACKBOARD_JSON_BEGIN');
  const endIdx = text.indexOf('BLACKBOARD_JSON_END');

  let blackboard = null;
  let cleanText = text;

  if (beginIdx >= 0) {
    if (endIdx > beginIdx) {
      // 完整的 blackboard 块：解析 JSON 并移除整个块
      const jsonStart = beginIdx + 'BLACKBOARD_JSON_BEGIN'.length;
      let jsonStr = text.substring(jsonStart, endIdx).trim();

      // Remove markdown code blocks if present
      if (jsonStr.startsWith('```json')) {
        jsonStr = jsonStr.substring(7).trim();
      }
      if (jsonStr.startsWith('```')) {
        jsonStr = jsonStr.substring(3).trim();
      }
      if (jsonStr.endsWith('```')) {
        jsonStr = jsonStr.substring(0, jsonStr.length - 3).trim();
      }

      try {
        blackboard = JSON.parse(jsonStr);
      } catch (e) {
        console.error('[A2A] Failed to parse blackboard JSON:', e);
      }

      // Remove blackboard section from display text
      cleanText = text.substring(0, beginIdx) + text.substring(endIdx + 'BLACKBOARD_JSON_END'.length);
    } else {
      // 不完整的 blackboard 块（流式输出中）：隐藏 BEGIN 之后的所有内容
      cleanText = text.substring(0, beginIdx);
    }
  }

  // Check for WAIT_USER marker
  const waitUser = text.includes('WAIT_USER') || text.includes('[NEED_USER_INPUT_BEGIN]');

  if (waitUser) {
    cleanText = cleanText
      .replace(/WAIT_USER/g, '')
      .replace(/\[NEED_USER_INPUT_BEGIN\]/g, '')
      .replace(/\[NEED_USER_INPUT_END\]/g, '')
      .trim();
  }

  return { cleanText: cleanText.trim(), blackboard, waitUser };
}

/**
 * 处理异步工作流事件
 */
function handleAsyncEvent(ev: any, _sessionId: string, set: any, get: any) {
  const eventType = ev.event;
  let data: any;

  try {
    data = JSON.parse(ev.data);
  } catch (e) {
    console.error('[A2A] Failed to parse event data:', e);
    return;
  }

  const { messages } = get();

  switch (eventType) {
    case 'planning_start':
      set({
        messages: [...messages, {
          agentId: 'system',
          agentName: 'System',
          content: '🤔 正在分析你的需求并制定执行计划...',
          timestamp: new Date().toISOString(),
          type: 'text'
        }]
      });
      break;

    case 'planner_delta':
      // 不再处理 planner delta，用户不需要看到原始 Planner 输出
      break;

    case 'plan_generated':
      set({
        messages: [...messages, {
          agentId: 'system',
          agentName: 'Planner',
          content: data.formattedPlan || `📋 执行计划已生成\n目标: ${data.goal}\n步骤数: ${data.stepCount}`,
          timestamp: new Date().toISOString(),
          type: 'text',  // 改为text类型，使用常规消息显示
          planId: data.planId,
          goal: data.goal
        }]
      });
      break;

    case 'need_clarification':
      set({
        messages: [...messages, {
          agentId: 'system',
          agentName: 'System',
          content: `❓ 需要澄清: ${data.question}`,
          timestamp: new Date().toISOString(),
          type: 'text'
        }],
        isLoading: false
      });
      break;

    case 'plan_failed':
      set({
        messages: [...messages, {
          agentId: 'system',
          agentName: 'System',
          content: `❌ 无法生成计划: ${data.reason}`,
          timestamp: new Date().toISOString(),
          type: 'text'
        }],
        isLoading: false
      });
      break;

    case 'step_start':
      set({
        messages: [...messages, {
          agentId: data.assignedAgentId,
          agentName: data.assignedAgentName,
          content: '',
          timestamp: new Date().toISOString(),
          type: 'text',
          stepId: data.stepId,
          stepIndex: data.stepIndex,
          stepDescription: data.description
        }]
      });
      break;

    case 'worker_delta':
      appendToLastMessage(data.delta, null, null, data.stepId, set, get);
      break;

    case 'step_completed':
      const lastMsg = messages[messages.length - 1];
      if (lastMsg && lastMsg.stepId === data.stepId) {
        set({
          messages: [...messages.slice(0, -1), {
            ...lastMsg,
            content: lastMsg.content + '\n✅ 步骤完成'
          }]
        });
      }
      break;

    case 'step_blocked':
      set({
        messages: [...messages, {
          agentId: 'system',
          agentName: 'System',
          content: `⚠️ 步骤 ${data.stepIndex} 遇到阻塞: ${data.blockedReason}`,
          timestamp: new Date().toISOString(),
          type: 'text'
        }]
      });
      break;

    case 'replanning_start':
      set({
        messages: [...messages, {
          agentId: 'system',
          agentName: 'System',
          content: `🔄 正在重新规划: ${data.reason}`,
          timestamp: new Date().toISOString(),
          type: 'text'
        }]
      });
      break;

    case 'replanner_delta':
      appendToLastMessage(data.delta, 'system', 'Replanner', null, set, get);
      break;

    case 'plan_updated':
      set({
        messages: [...messages, {
          agentId: 'system',
          agentName: 'System',
          content: `📋 计划已更新 (重规划次数: ${data.replanCount})`,
          timestamp: new Date().toISOString(),
          type: 'text'
        }]
      });
      break;

    case 'session_pause':
      set({
        messages: [...messages, {
          agentId: 'system',
          agentName: 'System',
          content: `⏸️ 会话已暂停: ${data.reason}`,
          timestamp: new Date().toISOString(),
          type: 'text'
        }],
        isLoading: false
      });
      break;

    case 'session_complete':
      set({
        messages: [...messages, {
          agentId: 'system',
          agentName: 'System',
          content: `✅ 任务完成！共完成 ${data.totalStepsExecuted || 0} 个步骤`,
          timestamp: new Date().toISOString(),
          type: 'text'
        }],
        isLoading: false
      });
      break;

    case 'error':
      set({
        messages: [...messages, {
          agentId: 'system',
          agentName: 'System',
          content: `❌ 错误: ${data.error}`,
          timestamp: new Date().toISOString(),
          type: 'text'
        }],
        error: data.error,
        isLoading: false
      });
      break;

    default:
      console.warn('[A2A] Unknown event type:', eventType, data);
  }
}

/**
 * 追加内容到最后一条消息
 */
function appendToLastMessage(delta: string, agentId: string | null, agentName: string | null, stepId: string | null, set: any, get: any) {
  const { messages } = get();
  const lastMsg = messages[messages.length - 1];

  // 不清理 artifact 标签，直接追加
  // 流式传输时保持原始内容，UI 层负责处理

  if (lastMsg && (
    (stepId && lastMsg.stepId === stepId) ||
    (!stepId && agentId && lastMsg.agentId === agentId)
  )) {
    // 匹配成功，追加到现有消息
    set({
      messages: [...messages.slice(0, -1), {
        ...lastMsg,
        content: lastMsg.content + delta
      }]
    });
  } else if (stepId) {
    // 如果有 stepId，尝试向前查找对应的消息
    const targetIndex = messages.findLastIndex((msg: A2AMessage) => msg.stepId === stepId);
    if (targetIndex >= 0) {
      const targetMsg = messages[targetIndex];
      set({
        messages: [
          ...messages.slice(0, targetIndex),
          {
            ...targetMsg,
            content: targetMsg.content + delta
          },
          ...messages.slice(targetIndex + 1)
        ]
      });
    } else {
      // 找不到对应的步骤消息，记录警告但不创建新消息
      console.warn('[A2A] Cannot find message with stepId:', stepId, ', dropping delta');
    }
  } else {
    // 没有 stepId，创建新消息（保留原逻辑用于其他事件）
    set({
      messages: [...messages, {
        agentId: agentId || 'system',
        agentName: agentName || 'System',
        content: delta,
        timestamp: new Date().toISOString(),
        type: 'text'
      }]
    });
  }
}

export const useA2AStore = create<A2AState>((set, get) => ({
  sessions: [],
  currentSession: null,
  messages: [],
  isLoading: false,
  error: null,

  fetchSessions: async () => {
    set({ isLoading: true, error: null });
    try {
      const sessions = await api.sessions.list();
      set({ sessions, isLoading: false });
    } catch (error: any) {
      console.warn("Failed to fetch sessions:", error);
      set({ sessions: [], error: error.message, isLoading: false });
    }
  },

  selectSession: async (session) => {
    get().disconnectStream();
    set({ currentSession: session, messages: [], isLoading: true });

    try {
      const dbMessages = await api.messages.getBySession(session.id);
      const a2aMessages: A2AMessage[] = dbMessages.map(msg => {
        const isUser = msg.role === 'user';
        // 解析 BLACKBOARD_JSON 和 WAIT_USER 标记
        const { cleanText, blackboard, waitUser } = parseModeratorMessage(msg.content);

        // 保留 artifact 标签，前端渲染时解析
        return {
          agentId: isUser ? 'human' : (msg.agentId?.toString() || 'assistant'),
          agentName: msg.agentName || (isUser ? 'User' : 'Agent'),
          content: cleanText,  // 保留 artifact 标签
          timestamp: msg.createdAt,
          type: (msg.messageType as 'text' | 'tool_use' | 'tool_result') || 'text',
          blackboardState: blackboard || undefined,
          waitingUser: waitUser || undefined
        };
      });
      set({ messages: a2aMessages, isLoading: false });
    } catch (error: any) {
      console.warn('Failed to fetch messages:', error);
      set({ messages: [], isLoading: false });
    }
  },

  clearSession: () => {
    get().disconnectStream();
    set({ currentSession: null, messages: [] });
  },

  // V2 Group Chat - Async workflow-based orchestration
  startGroupChatV2: async (sessionId, userMessage) => {
    console.log('[A2A V2] Starting group chat:', { sessionId, userMessage });

    // 1. Disconnect any existing connection
    const { disconnectStream } = get();
    disconnectStream();

    set({ isLoading: true, error: null });

    // 2. Optimistic update: display user message immediately
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
      // 3. Submit request (Fire-and-Forget)
      const submitResponse = await api.groupChat.submit(sessionId, userMessage || '');
      console.log('[A2A V2] Submit response:', submitResponse);

      // 4. Subscribe to SSE event stream (独立连接)
      const eventSourceUrl = `/api/group-chat/${sessionId}/events`;
      currentAbortController = new AbortController();

      fetchEventSource(eventSourceUrl, {
        signal: currentAbortController.signal,

        onmessage(ev) {
          handleAsyncEvent(ev, sessionId, set, get);
        },

        onerror(err) {
          if (err.name !== 'AbortError') {
            console.error('[A2A V2 SSE] Error:', err);
            set({ error: 'Stream connection error', isLoading: false });
          }
        },

        onclose() {
          console.log('[A2A V2 SSE] Connection closed');
          set({ isLoading: false });
        }
      }).catch(err => {
        if (err.name !== 'AbortError') {
          console.error('[A2A V2 SSE] Connection error:', err);
          set({ error: err.message, isLoading: false });
        }
      });

    } catch (error: any) {
      console.error('[A2A V2] Failed to start group chat:', error);
      set({ error: 'Failed to start conversation', isLoading: false });
    }
  },

  disconnectStream: () => {
    if (currentAbortController) {
      try {
        currentAbortController.abort();
      } catch (e) {
        // Abort 可能在连接已关闭时抛出异常，这是正常的
        console.debug('[A2A] Abort completed:', e instanceof Error ? e.message : e);
      } finally {
        currentAbortController = null;
      }
    }
  },

  stopStream: async () => {
    const currentSession = get().currentSession;
    if (!currentSession) return;

    console.log('[A2A] Stopping stream for session:', currentSession.id);

    // 复用 disconnectStream 统一处理
    get().disconnectStream();

    // Set loading to false
    set({ isLoading: false });

    // Then notify backend to stop
    try {
      await api.groupChat.stop(currentSession.id);
      console.log('[A2A] Stream stopped successfully');
    } catch (error: any) {
      console.error('[A2A] Failed to stop stream:', error);
    }
  }
}));
