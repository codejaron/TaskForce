import { create } from 'zustand';
import { api } from '../../../shared/api';
import type { Session, A2AMessage, ToolCallDTO } from '../../../shared/api';
import { fetchEventSource } from '@microsoft/fetch-event-source';

interface A2AState {
  sessions: Session[];
  currentSession: Session | null;
  messages: A2AMessage[];
  toolCallsByStepId: Record<string, ToolCallDTO[]>;
  workflowStatus: string | null;  // 后端状态：PLANNING, EXECUTING, REPLANNING, PAUSED, COMPLETED, FAILED
  error: string | null;

  fetchSessions: () => Promise<void>;
  selectSession: (session: Session) => Promise<void>;
  clearSession: () => void;

  startGroupChatV2: (sessionId: string, userMessage: string | null) => void;
  disconnectStream: () => void;
  stopStream: () => Promise<void>;
}

// 存储当前的 AbortController，用于关闭 SSE 连接
let currentAbortController: AbortController | null = null;

// 存储重连定时器
let reconnectTimeoutId: ReturnType<typeof setTimeout> | null = null;

// ========== 重连配置 ==========
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

// ========== 去重逻辑 ==========
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

/**
 * 解析 Moderator 消息中的特殊标记
 */
function parseModeratorMessage(text: string): { cleanText: string; blackboard: any | null; waitUser: boolean } {
  const beginIdx = text.indexOf('BLACKBOARD_JSON_BEGIN');
  const endIdx = text.indexOf('BLACKBOARD_JSON_END');

  let blackboard = null;
  let cleanText = text;

  if (beginIdx >= 0) {
    if (endIdx > beginIdx) {
      const jsonStart = beginIdx + 'BLACKBOARD_JSON_BEGIN'.length;
      let jsonStr = text.substring(jsonStart, endIdx).trim();

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

      cleanText = text.substring(0, beginIdx) + text.substring(endIdx + 'BLACKBOARD_JSON_END'.length);
    } else {
      cleanText = text.substring(0, beginIdx);
    }
  }

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
 * 关键：每个事件都带 status 字段，前端直接更新 workflowStatus
 */
function handleAsyncEvent(ev: any, sessionId: string, set: any, get: any) {
  const eventType = ev.event;
  let data: any;

  try {
    data = JSON.parse(ev.data);
  } catch (e) {
    console.error('[A2A] Failed to parse event data:', e);
    return;
  }

  // ========== 去重逻辑 ==========
  const eventId = data.eventId;
  if (eventId) {
    const eventIdSet = getEventIdSet(sessionId);
    if (eventIdSet.has(eventId)) {
      console.debug('[A2A] Duplicate event ignored:', eventId, eventType);
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

  const { messages } = get();

  // 从事件中提取状态（后端每个事件都应该带 status）
  const newStatus = data.status || null;

  switch (eventType) {
    case 'planning_start':
      set({
        workflowStatus: newStatus || 'PLANNING',
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
      break;

    case 'plan_generated':
      set({
        workflowStatus: newStatus || 'EXECUTING',
        messages: [...messages, {
          agentId: 'system',
          agentName: 'Planner',
          content: data.formattedPlan || `📋 执行计划已生成\n目标: ${data.goal}\n步骤数: ${data.stepCount}`,
          timestamp: new Date().toISOString(),
          type: 'text',
          planId: data.planId,
          goal: data.goal
        }]
      });
      break;

    case 'need_clarification':
      set({
        workflowStatus: newStatus || 'PAUSED',
        messages: [...messages, {
          agentId: 'system',
          agentName: 'System',
          content: `❓ 需要澄清: ${data.question}`,
          timestamp: new Date().toISOString(),
          type: 'text'
        }]
      });
      break;

    case 'plan_failed':
      set({
        workflowStatus: newStatus || 'FAILED',
        messages: [...messages, {
          agentId: 'system',
          agentName: 'System',
          content: `❌ 无法生成计划: ${data.reason}`,
          timestamp: new Date().toISOString(),
          type: 'text'
        }]
      });
      break;

    case 'step_start':
      {
        // 检查是否已存在相同 stepId 的消息
        const hasExisting = messages.some((msg: A2AMessage) => msg.stepId === data.stepId);

        // 如果已存在，给新消息的 stepId 加后缀区分
        const newStepId = hasExisting ? `${data.stepId}_${Date.now()}` : data.stepId;

        set({
          workflowStatus: newStatus || 'EXECUTING',
          messages: [...messages, {
            agentId: data.assignedAgentId,
            agentName: data.assignedAgentName,
            content: '',
            timestamp: new Date().toISOString(),
            type: 'text',
            stepId: newStepId,
            originalStepId: data.stepId,  // 保留原始 stepId 用于工具卡片关联
            stepIndex: data.stepIndex,
            stepDescription: data.description
          }]
        });
      }
      break;

    case 'worker_delta':
      // delta 不改状态，只追加内容
      appendToLastMessage(data.delta, null, null, data.stepId, set, get);
      break;

    case 'step_completed':
      {
        // 找最后一条匹配的消息
        const targetIndex = messages.findLastIndex((msg: A2AMessage) =>
          msg.stepId === data.stepId || msg.originalStepId === data.stepId
        );
        if (targetIndex >= 0) {
          const targetMsg = messages[targetIndex];
          set({
            workflowStatus: newStatus || get().workflowStatus,
            messages: [
              ...messages.slice(0, targetIndex),
              { ...targetMsg, content: targetMsg.content + '\n✅ 步骤完成' },
              ...messages.slice(targetIndex + 1)
            ]
          });
        }
      }
      break;

    case 'step_blocked':
      set({
        workflowStatus: newStatus || get().workflowStatus,
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
        workflowStatus: newStatus || 'REPLANNING',
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
        workflowStatus: newStatus || 'EXECUTING',
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
      // 只更新状态，不添加消息（stopStream 已经处理了 UI 反馈）
      set({ workflowStatus: 'PAUSED' });
      break;

    case 'session_complete':
      set({
        workflowStatus: 'COMPLETED',
        messages: [...messages, {
          agentId: 'system',
          agentName: 'System',
          content: `✅ 任务完成！共完成 ${data.totalStepsExecuted || 0} 个步骤`,
          timestamp: new Date().toISOString(),
          type: 'text'
        }]
      });
      break;

    case 'error':
      set({
        workflowStatus: 'FAILED',
        messages: [...messages, {
          agentId: 'system',
          agentName: 'System',
          content: `❌ 错误: ${data.error}`,
          timestamp: new Date().toISOString(),
          type: 'text'
        }],
        error: data.error
      });
      break;

    case 'tool_call_start':
      {
        const originalStepId = data.stepId || 'unknown';
        const { toolCallsByStepId } = get();

        // 找最新的消息，获取它的 stepId（可能带后缀）
        const latestMsg = messages.findLast((msg: A2AMessage) =>
          msg.stepId === originalStepId || msg.originalStepId === originalStepId
        );
        const actualStepId = latestMsg?.stepId || originalStepId;

        const existingCalls = toolCallsByStepId[actualStepId] || [];

        // 去重
        if (existingCalls.some(tc => tc.toolCallId === data.toolCallId)) {
          break;
        }

        const newToolCall: ToolCallDTO = {
          toolCallId: data.toolCallId,
          toolName: data.toolName,
          serverName: data.serverName,
          toolArgs: data.toolArgs,
          status: 'RUNNING',
          sequence: data.sequence || 0,
          stepId: actualStepId
        };
        set({
          toolCallsByStepId: {
            ...toolCallsByStepId,
            [actualStepId]: [...existingCalls, newToolCall]
          }
        });
      }
      break;

    case 'tool_call_complete':
      {
        const originalStepId = data.stepId || 'unknown';
        const { toolCallsByStepId } = get();

        // 找最新的消息，获取它的 stepId
        const latestMsg = messages.findLast((msg: A2AMessage) =>
          msg.stepId === originalStepId || msg.originalStepId === originalStepId
        );
        const actualStepId = latestMsg?.stepId || originalStepId;

        const existingCalls = toolCallsByStepId[actualStepId] || [];
        const updatedCalls = existingCalls.map(tc =>
          tc.toolCallId === data.toolCallId
            ? {
                ...tc,
                toolResult: data.toolResult,
                status: data.status as 'SUCCESS' | 'FAILED',
                errorMessage: data.errorMessage,
                durationMs: data.durationMs
              }
            : tc
        );
        set({
          toolCallsByStepId: {
            ...toolCallsByStepId,
            [actualStepId]: updatedCalls
          }
        });
      }
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

  if (lastMsg && (
    (stepId && (lastMsg.stepId === stepId || lastMsg.originalStepId === stepId)) ||
    (!stepId && agentId && lastMsg.agentId === agentId)
  )) {
    set({
      messages: [...messages.slice(0, -1), {
        ...lastMsg,
        content: lastMsg.content + delta
      }]
    });
  } else if (stepId) {
    // 找最后一条匹配的消息（可能是原始 stepId 或带后缀的）
    const targetIndex = messages.findLastIndex((msg: A2AMessage) =>
      msg.stepId === stepId || msg.originalStepId === stepId
    );
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
      console.warn('[A2A] Cannot find message with stepId:', stepId, ', dropping delta');
    }
  } else {
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
  toolCallsByStepId: {},
  workflowStatus: null,
  error: null,

  fetchSessions: async () => {
    try {
      const sessions = await api.sessions.list();
      set({ sessions });
    } catch (error: any) {
      console.warn("Failed to fetch sessions:", error);
      set({ sessions: [], error: error.message });
    }
  },

  selectSession: async (session) => {
    const oldSession = get().currentSession;
    if (oldSession) {
      clearEventIdSet(oldSession.id);
    }

    get().disconnectStream();
    set({ currentSession: session, messages: [], toolCallsByStepId: {}, workflowStatus: null });

    try {
      // 并行加载消息、工具调用、当前状态
      const [dbMessages, toolCalls, stateResponse] = await Promise.all([
        api.messages.getBySession(session.id),
        api.toolCalls.getBySession(session.id),
        api.groupChat.getState(session.id).catch(() => null)
      ]);

      // 将工具调用按 stepId 分组
      const toolCallsByStepId: Record<string, ToolCallDTO[]> = {};
      for (const tc of toolCalls) {
        const stepId = tc.stepId || 'unknown';
        if (!toolCallsByStepId[stepId]) {
          toolCallsByStepId[stepId] = [];
        }
        toolCallsByStepId[stepId].push(tc);
      }

      const a2aMessages: A2AMessage[] = dbMessages.map(msg => {
        const isUser = msg.role === 'user';
        const { cleanText, blackboard, waitUser } = parseModeratorMessage(msg.content);

        return {
          agentId: isUser ? 'human' : (msg.agentId?.toString() || 'assistant'),
          agentName: msg.agentName || (isUser ? 'User' : 'Agent'),
          content: cleanText,
          timestamp: msg.createdAt,
          type: (msg.messageType as 'text' | 'tool_use' | 'tool_result') || 'text',
          blackboardState: blackboard || undefined,
          waitingUser: waitUser || undefined
        };
      });

      // 从后端获取当前状态
      const workflowStatus = stateResponse?.status || null;

      set({ messages: a2aMessages, toolCallsByStepId, workflowStatus });
    } catch (error: any) {
      console.warn('Failed to fetch messages:', error);
      set({ messages: [], toolCallsByStepId: {}, workflowStatus: null });
    }
  },

  clearSession: () => {
    const currentSession = get().currentSession;
    if (currentSession) {
      clearEventIdSet(currentSession.id);
    }
    get().disconnectStream();
    set({ currentSession: null, messages: [], workflowStatus: null });
  },

  startGroupChatV2: async (sessionId, userMessage) => {
    console.log('[A2A V2] Sending message:', { sessionId, userMessage });

    const { disconnectStream } = get();
    disconnectStream();

    // 立即设置为 EXECUTING 状态（乐观更新）
    set({ workflowStatus: 'EXECUTING', error: null });

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
      // 调用统一接口，后端自动判断 submit 还是 resume
      const submitResponse = await api.groupChat.message(sessionId, userMessage || '');
      console.log('[A2A V2] Message response:', submitResponse);

      // 建立 SSE 连接
      const eventSourceUrl = `/api/group-chat/${sessionId}/events`;
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

          onmessage(ev) {
            if (reconnectAttempts > 0) {
              console.log('[A2A V2 SSE] Reconnected successfully');
              reconnectAttempts = 0;
            }
            handleAsyncEvent(ev, sessionId, set, get);
          },

          onerror(err) {
            if (err.name === 'AbortError') {
              return;
            }

            console.error('[A2A V2 SSE] Connection error:', err);

            if (reconnectAttempts < RECONNECT_CONFIG.maxRetries) {
              const delay = getReconnectDelay(reconnectAttempts);
              reconnectAttempts++;

              console.log(`[A2A V2 SSE] Reconnecting in ${Math.round(delay)}ms (attempt ${reconnectAttempts}/${RECONNECT_CONFIG.maxRetries})`);

              reconnectTimeoutId = setTimeout(() => {
                connectSSE();
              }, delay);
            } else {
              console.error('[A2A V2 SSE] Max reconnection attempts reached');
              set({
                error: 'Connection lost. Please refresh the page.',
                workflowStatus: 'FAILED'
              });
            }
          },

          onclose() {
            console.log('[A2A V2 SSE] Connection closed');
            cleanupReconnect();

            // 主动断开不更新状态
            if (controller.signal.aborted) {
              console.log('[A2A V2 SSE] Connection aborted intentionally');
              return;
            }

            // 非主动断开，尝试重连
            if (reconnectAttempts < RECONNECT_CONFIG.maxRetries) {
              const delay = getReconnectDelay(reconnectAttempts);
              reconnectAttempts++;

              console.log(`[A2A V2 SSE] Reconnecting after close (attempt ${reconnectAttempts}/${RECONNECT_CONFIG.maxRetries})`);

              reconnectTimeoutId = setTimeout(() => {
                connectSSE();
              }, delay);
            }
          }
        }).catch(err => {
          if (err.name !== 'AbortError') {
            console.error('[A2A V2 SSE] Fatal error:', err);
            set({ error: err.message, workflowStatus: 'FAILED' });
          }
        });
      };

      connectSSE();

    } catch (error: any) {
      console.error('[A2A V2] Failed to start group chat:', error);
      set({ error: 'Failed to start conversation', workflowStatus: 'FAILED' });
    }
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

    // 立即设置为 PAUSED
    set({ workflowStatus: 'PAUSED' });

    try {
      await api.groupChat.stop(currentSession.id);
      console.log('[A2A] Stream stopped successfully');
    } catch (error: any) {
      console.error('[A2A] Failed to stop stream:', error);
    }

    get().disconnectStream();
  }
}));
