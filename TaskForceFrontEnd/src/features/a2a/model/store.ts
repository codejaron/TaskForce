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
  deleteSession: (sessionId: string) => Promise<void>;
  refreshMessages: () => Promise<void>;  // 新增：刷新当前会话消息

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
 * 处理异步工作流事件
 * 关键：每个事件都带 status 字段，前端直接更新 workflowStatus
 */
function handleAsyncEvent(
  ev: { event: string; data: string },
  sessionId: string,
  set: (partial: A2AState | Partial<A2AState> | ((state: A2AState) => A2AState | Partial<A2AState>), replace?: false) => void,
  get: () => A2AState
) {
  const eventType = ev.event;
  let eventData: unknown;

  try {
    eventData = JSON.parse(ev.data);
  } catch (e) {
    console.error('[A2A] Failed to parse event data:', e);
    return;
  }

  const data = (eventData && typeof eventData === 'object') ? (eventData as Record<string, unknown>) : {};
  const getStr = (v: unknown, fallback = ''): string => (typeof v === 'string' ? v : fallback);
  const getNum = (v: unknown, fallback = 0): number => (typeof v === 'number' ? v : fallback);

  // ========== 去重逻辑 ==========
  const eventId = data.eventId;
  if (typeof eventId === 'string' && eventId) {
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
  const newStatus = typeof data.status === 'string' ? data.status : null;

  // 便捷提取（避免 unknown 直接当 string/number 用）
  const stepIdStr = typeof data.stepId === 'string' ? data.stepId : null;

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
      // planner_delta 是 JSON 流式输出，不直接显示
      // 只在规划中显示进度提示（已在 planning_start 中显示）
      break;

    case 'plan_generated':
      {
        // 使用 formattedPlan 显示格式化的计划
        const planContent = (typeof data.formattedPlan === 'string' && data.formattedPlan)
          ? data.formattedPlan
          : `📋 执行计划已生成\n目标: ${getStr(data.goal)}\n步骤数: ${getNum(data.stepCount)}`;
        
        set({
          workflowStatus: newStatus || 'EXECUTING',
          messages: [...messages, {
            agentId: 'system',
            agentName: 'Planner',
            content: planContent,
            timestamp: new Date().toISOString(),
            type: 'text',
            planId: typeof data.planId === 'string' ? data.planId : undefined,
            goal: typeof data.goal === 'string' ? data.goal : undefined
          }]
        });
      }
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

    case 'layer_start':
      {
        const layerIndex = getNum(data.layerIndex, 0);
        const stepIds = Array.isArray(data.stepIds) ? data.stepIds : [];
        const stepCount = getNum(data.stepCount, 0);

        set({
          workflowStatus: newStatus || 'EXECUTING',
          messages: [...messages, {
            agentId: 'system',
            agentName: 'System',
            content: `🔄 开始执行第 ${layerIndex + 1} 层（${stepCount} 个并行步骤）`,
            timestamp: new Date().toISOString(),
            type: 'text',
            layerIndex,
            stepIds: stepIds as string[]
          }]
        });
      }
      break;

    case 'layer_complete':
      {
        const layerIndex = getNum(data.layerIndex, 0);
        const successCount = getNum(data.successCount, 0);
        const failedCount = getNum(data.failedCount, 0);

        set({
          workflowStatus: newStatus || get().workflowStatus,
          messages: [...messages, {
            agentId: 'system',
            agentName: 'System',
            content: `✅ 第 ${layerIndex + 1} 层完成（成功: ${successCount}, 失败: ${failedCount}）`,
            timestamp: new Date().toISOString(),
            type: 'text',
            layerIndex
          }]
        });
      }
      break;

    case 'step_start':
      {
        const stepId = getStr(data.stepId, 'unknown');

        const hasExisting = messages.some((msg: A2AMessage) => msg.stepId === stepId);
        const newStepId = hasExisting ? `${stepId}_${Date.now()}` : stepId;

        set({
          workflowStatus: newStatus || 'EXECUTING',
          messages: [...messages, {
            agentId: getStr(data.assignedAgentId, 'unknown'),
            agentName: getStr(data.assignedAgentName, 'unknown'),
            content: '',
            timestamp: new Date().toISOString(),
            type: 'text',
            stepId: newStepId,
            originalStepId: stepId,
            stepIndex: typeof data.stepIndex === 'number' ? data.stepIndex : undefined,
            stepDescription: typeof data.instruction === 'string' ? data.instruction : undefined
          }]
        });
      }
      break;

    case 'worker_delta':
      // delta 不改状态，只追加内容
      appendToLastMessage(typeof data.delta === 'string' ? data.delta : '', null, null, stepIdStr, set, get);
      break;

    case 'step_completed':
      {
        const completedStepId = typeof data.stepId === 'string' ? data.stepId : '';
        const targetIndex = findLastIndexCompat(messages, (msg: A2AMessage) =>
          msg.stepId === completedStepId || msg.originalStepId === completedStepId
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
      appendToLastMessage(typeof data.delta === 'string' ? data.delta : '', 'system', 'Replanner', null, set, get);
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
          content: `❌ 错误: ${getStr(data.error)}`,
          timestamp: new Date().toISOString(),
          type: 'text'
        }],
        error: typeof data.error === 'string' ? data.error : 'Unknown error'
      });
      break;

    case 'tool_call_start':
      {
        const originalStepId = typeof data.stepId === 'string' ? data.stepId : 'unknown';
        const { toolCallsByStepId } = get();

        const latestMsg = findLastCompat(messages, (msg: A2AMessage) =>
          msg.stepId === originalStepId || msg.originalStepId === originalStepId
        );
        const actualStepId = latestMsg?.stepId || originalStepId;

        const existingCalls = toolCallsByStepId[actualStepId] || [];

        const toolCallId = typeof data.toolCallId === 'string' ? data.toolCallId : '';
        if (!toolCallId) {
          break;
        }

        // 去重
        if (existingCalls.some((tc: ToolCallDTO) => tc.toolCallId === toolCallId)) {
          break;
        }

        const seq = typeof data.sequence === 'number' ? data.sequence : 0;

        const newToolCall: ToolCallDTO = {
          toolCallId,
          toolName: typeof data.toolName === 'string' ? data.toolName : 'unknown',
          serverName: typeof data.serverName === 'string' ? data.serverName : undefined,
          toolArgs: typeof data.toolArgs === 'string' ? data.toolArgs : '{}',
          status: 'RUNNING',
          sequence: seq,
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
        const originalStepId = typeof data.stepId === 'string' ? data.stepId : 'unknown';
        const { toolCallsByStepId } = get();

        const latestMsg = findLastCompat(messages, (msg: A2AMessage) =>
          msg.stepId === originalStepId || msg.originalStepId === originalStepId
        );
        const actualStepId = latestMsg?.stepId || originalStepId;

        const toolCallId = typeof data.toolCallId === 'string' ? data.toolCallId : '';
        if (!toolCallId) {
          break;
        }

        const existingCalls = toolCallsByStepId[actualStepId] || [];
        const updatedCalls = existingCalls.map((tc: ToolCallDTO) =>
          tc.toolCallId === toolCallId
            ? {
                ...tc,
                toolResult: typeof data.toolResult === 'string' ? data.toolResult : undefined,
                status: (data.status === 'SUCCESS' || data.status === 'FAILED')
                  ? (data.status as 'SUCCESS' | 'FAILED')
                  : 'SUCCESS',
                errorMessage: typeof data.errorMessage === 'string' ? data.errorMessage : undefined,
                durationMs: typeof data.durationMs === 'number' ? data.durationMs : undefined
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

// 兼容：在不支持 findLast/findLastIndex 的环境里获取最后一个匹配项/索引
function findLastIndexCompat<T>(arr: T[], predicate: (value: T, index: number) => boolean): number {
  for (let i = arr.length - 1; i >= 0; i--) {
    if (predicate(arr[i], i)) return i;
  }
  return -1;
}

function findLastCompat<T>(arr: T[], predicate: (value: T, index: number) => boolean): T | undefined {
  const idx = findLastIndexCompat(arr, predicate);
  return idx >= 0 ? arr[idx] : undefined;
}

/**
 * 追加内容到最后一条消息
 */
function appendToLastMessage(
  delta: string,
  agentId: string | null,
  agentName: string | null,
  stepId: string | null,
  set: (partial: A2AState | Partial<A2AState> | ((state: A2AState) => A2AState | Partial<A2AState>), replace?: false) => void,
  get: () => A2AState
) {
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
    const targetIndex = findLastIndexCompat(messages, (msg: A2AMessage) =>
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

/**
 * 抽取加载消息的公共逻辑
 * 用于 selectSession 和 refreshMessages
 */
async function loadSessionMessages(
  sessionId: string,
  set: (partial: Partial<A2AState>) => void
) {
  const [dbMessages, toolCalls] = await Promise.all([
    api.messages.getBySession(sessionId),
    api.toolCalls.getBySession(sessionId)
  ]);

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
    
    // 映射 messageType 到前端的 type
    let messageType: 'text' | 'tool_use' | 'tool_result' | 'plan' | 'question' = 'text';
    if (msg.messageType === 'PLANNER_MSG') {
      messageType = 'plan';
    } else if (msg.messageType === 'tool_use') {
      messageType = 'tool_use';
    } else if (msg.messageType === 'tool_result') {
      messageType = 'tool_result';
    }
    
    return {
      agentId: isUser ? 'human' : (msg.agentId?.toString() || 'assistant'),
      agentName: msg.agentName || (isUser ? 'User' : 'Agent'),
      content: msg.content,
      timestamp: msg.createdAt,
      type: messageType,
      isStreaming: msg.status === 'STREAMING',  // 从数据库字段映射
      stepId: msg.stepId  // 映射 stepId 以关联工具调用
    };
  });

  set({ messages: a2aMessages, toolCallsByStepId });
  return a2aMessages;
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
    } catch (error: unknown) {
      console.warn("Failed to fetch sessions:", error);
      set({ sessions: [], error: error instanceof Error ? error.message : String(error) });
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
      // 使用公共方法加载消息
      await loadSessionMessages(session.id, set);
      
      // 加载状态
      const stateResponse = await api.groupChat.getState(session.id).catch(() => null);
      const workflowStatus = stateResponse?.status || null;
      set({ workflowStatus });
    } catch (error: unknown) {
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

  deleteSession: async (sessionId: string) => {
    try {
      await api.sessions.delete(sessionId);
      const currentSession = get().currentSession;

      // If the deleted session is the current one, clear it
      if (currentSession?.id === sessionId) {
        get().clearSession();
      }

      // Remove the session from the list
      set({ sessions: get().sessions.filter(s => s.id !== sessionId) });
    } catch (error: unknown) {
      console.error('Failed to delete session:', error);
      throw error;
    }
  },

  // 新增：刷新当前会话消息
  refreshMessages: async () => {
    const currentSession = get().currentSession;
    if (!currentSession) return;

    try {
      console.log('[A2A] Refreshing messages for session:', currentSession.id);
      await loadSessionMessages(currentSession.id, set);
    } catch (error) {
      console.error('[A2A] Failed to refresh messages:', error);
    }
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
      // 🔥 关键修改：先建立 SSE 连接，再提交消息
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

          async onopen(response) {
            if (response.ok) {
              console.log('[A2A V2 SSE] ✅ Connection established');
              
              // 重连时重新加载消息
              if (reconnectAttempts > 0) {
                console.log('[A2A V2 SSE] Reloading messages after reconnect...');
                try {
                  await loadSessionMessages(sessionId, set);
                } catch (e) {
                  console.error('[A2A V2 SSE] Failed to reload messages:', e);
                }
              }
            } else {
              throw new Error(`SSE connection failed with status ${response.status}`);
            }
          },

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

      // 启动 SSE 连接
      connectSSE();

      // 🔥 等待 SSE 连接建立后再提交消息（给 50ms 缓冲时间）
      setTimeout(async () => {
        try {
          console.log('[A2A V2] Submitting message to backend:', userMessage);
          const submitResponse = await api.groupChat.message(sessionId, userMessage || '');
          console.log('[A2A V2] ✅ Message submitted successfully:', submitResponse);
        } catch (submitError) {
          console.error('[A2A V2] ❌ Failed to submit message:', submitError);
          set({ error: 'Failed to submit message', workflowStatus: 'FAILED' });
        }
      }, 50);

    } catch (error: unknown) {
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
    } catch (error: unknown) {
      console.error('[A2A] Failed to stop stream:', error);
    }

    get().disconnectStream();
  }
}));
