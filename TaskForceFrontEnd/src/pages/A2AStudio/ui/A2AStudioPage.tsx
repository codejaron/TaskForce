import React, { useEffect, useState, useRef } from 'react';
import { useA2AStore } from '../../../features/a2a/model/store';
import { useAgentStore } from '../../../features/agents/model/store';
import { api } from '../../../shared/api';
import { useTranslation } from 'react-i18next';
import { parseArtifacts, stripArtifactTags } from '../../../utils/parseArtifacts';
import { ArtifactCard } from '../../../components/ArtifactCard';
import { ToolCallList } from '../../../components/ToolCallCard';
import {
  Plus,
  Users,
  Send,
  Bot,
  User,
  X,
  MessageCircle,
  ChevronLeft,
  ChevronRight,
  Loader2,
  Wrench,
  CheckCircle,
  StopCircle,
  ClipboardList,
  AlertCircle,
  MapPin,
  Trash2
} from 'lucide-react';
import { clsx } from 'clsx';
import ReactMarkdown from 'react-markdown';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneLight } from 'react-syntax-highlighter/dist/esm/styles/prism';
import remarkGfm from 'remark-gfm';
import type { Session } from '../../../shared/api/types';

export const A2AStudioPage: React.FC = () => {
  const { sessions, currentSession, messages, toolCallsByStepId, workflowStatus, fetchSessions, selectSession, startGroupChatV2, disconnectStream, stopStream, deleteSession, refreshMessages } = useA2AStore();
  const { agents, fetchAgents } = useAgentStore();
  const { t } = useTranslation();

  // 计算是否正在运行（用于显示停止按钮）
  const isRunning = workflowStatus === 'PLANNING' || workflowStatus === 'EXECUTING' || workflowStatus === 'REPLANNING';

  const [showCreateModal, setShowCreateModal] = useState(false);
  const [newSessionName, setNewSessionName] = useState('');
  const [selectedAgentIds, setSelectedAgentIds] = useState<string[]>([]);
  const [isCreating, setIsCreating] = useState(false);

  // Filter agents - only show WORKER type agents to users
  const workerAgents = agents.filter(agent => {
    const roleType = typeof (agent as { roleType?: unknown }).roleType === 'string'
      ? (agent as { roleType?: string }).roleType
      : 'WORKER';
    return roleType === 'WORKER';
  });

  // Find the moderator agent (will be automatically added to sessions)
  const moderatorAgent = agents.find(agent => {
    const roleType = typeof (agent as { roleType?: unknown }).roleType === 'string'
      ? (agent as { roleType?: string }).roleType
      : 'WORKER';
    return roleType === 'MODERATOR';
  });
  const moderatorId = moderatorAgent?.id;

  const [inputMessage, setInputMessage] = useState('');
  const [showSidebar, setShowSidebar] = useState(true);
  const [isUserScrolling, setIsUserScrolling] = useState(false);

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const messagesContainerRef = useRef<HTMLDivElement>(null);
  const lastScrollTopRef = useRef<number>(0);

  useEffect(() => {
    fetchSessions();
    fetchAgents();

    return () => {
      disconnectStream();
    };
  }, []);

  // 监听页面可见性，切回来时刷新消息
  useEffect(() => {
    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible' && currentSession) {
        console.log('[A2AStudioPage] Page visible, refreshing messages...');
        refreshMessages();
      }
    };

    document.addEventListener('visibilitychange', handleVisibilityChange);
    return () => document.removeEventListener('visibilitychange', handleVisibilityChange);
  }, [currentSession, refreshMessages]);

  useEffect(() => {
    // 只在用户没有手动滚动时自动滚动到底部
    if (!isUserScrolling) {
      messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
    }
  }, [messages, isUserScrolling]);

  // 监听滚动事件，检测用户是否主动向上滚动
  const handleScroll = () => {
    const container = messagesContainerRef.current;
    if (!container) return;

    const { scrollTop, scrollHeight, clientHeight } = container;
    const isAtBottom = scrollHeight - scrollTop - clientHeight < 50;

    // 检测用户是否主动向上滚动（而不是因为内容增加导致的被动滚动）
    const isScrollingUp = scrollTop < lastScrollTopRef.current;
    lastScrollTopRef.current = scrollTop;

    // 只有当用户主动向上滚动，且不在底部时，才标记为用户滚动
    if (isScrollingUp && !isAtBottom) {
      setIsUserScrolling(true);
    } else if (isAtBottom) {
      // 如果用户滚动到底部，恢复自动滚动
      setIsUserScrolling(false);
    }
  };

  const handleCreate = async () => {
    if (!newSessionName || selectedAgentIds.length < 1) {
      alert('Please enter a session name and select at least 1 agent');
      return;
    }

    setIsCreating(true);
    try {
      // Automatically add moderator to the beginning of agent list if it exists
      const agentIdsToSend = moderatorId
        ? [parseInt(moderatorId), ...selectedAgentIds.map(id => parseInt(id))]
        : selectedAgentIds.map(id => parseInt(id));

      const session = await api.sessions.create({
        name: newSessionName,
        type: 'GROUP',
        agentIds: agentIdsToSend
      });
      await fetchSessions();
      selectSession(session);
      setShowCreateModal(false);
      setNewSessionName('');
      setSelectedAgentIds([]);
    } catch (error) {
      console.error('Failed to create session:', error);
      alert('Failed to create session');
    } finally {
      setIsCreating(false);
    }
  };

  const handleSend = async () => {
    if (!inputMessage.trim() || !currentSession) return;
    const msg = inputMessage;
    setInputMessage('');
    startGroupChatV2(currentSession.id, msg);
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    // 检查是否正在使用输入法（中文、日文等）
    // isComposing 为 true 表示输入法正在组合字符，此时不应该提交
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleStop = async () => {
    await stopStream();
  };

  const handleDelete = async (sessionId: string, e: React.MouseEvent) => {
    e.stopPropagation(); // Prevent triggering the session selection

    if (window.confirm(t('a2a.deleteMessage'))) {
      try {
        await deleteSession(sessionId);
      } catch (error) {
        console.error('Failed to delete session:', error);
        alert('Failed to delete session');
      }
    }
  };

  const toggleAgentSelection = (agentId: string) => {
    setSelectedAgentIds(prev =>
      prev.includes(agentId)
        ? prev.filter(id => id !== agentId)
        : [...prev, agentId]
    );
  };

  return (
    <div className="h-full flex bg-white relative">
      {/* Sidebar - Sessions */}
      <div className={clsx(
        "flex-shrink-0 border-r border-gray-200 bg-gray-50 transition-all duration-300 relative",
        showSidebar ? "w-64" : "w-0 overflow-hidden"
      )}>
        <div className="h-full flex flex-col w-64">
          {/* Header */}
          <div className="p-4 border-b border-gray-200">
            <div className="flex items-center justify-between mb-4">
              <div className="flex items-center gap-2">
                <Users size={20} className="text-purple-600" />
                <h2 className="font-bold text-gray-900">{t('a2a.sessions')}</h2>
              </div>
              <button
                onClick={() => setShowCreateModal(true)}
                className="p-2 bg-purple-50 hover:bg-purple-100 text-purple-600 rounded-lg transition-colors duration-200 cursor-pointer"
              >
                <Plus size={18} />
              </button>
            </div>
          </div>

          {/* Sessions List */}
          <div className="flex-1 overflow-y-auto p-3 space-y-2">
            {sessions.length === 0 ? (
              <div className="flex items-center justify-center py-8">
                <Loader2 size={24} className="animate-spin text-gray-500" />
              </div>
            ) : sessions.length === 0 ? (
              <div className="text-center py-8">
                <MessageCircle size={32} className="mx-auto mb-3 text-gray-400" />
                <p className="text-sm text-gray-500">{t('a2a.noSessions')}</p>
                <button
                  onClick={() => setShowCreateModal(true)}
                  className="mt-3 text-sm text-purple-600 hover:text-purple-700 cursor-pointer"
                >
                  {t('a2a.createFirst')}
                </button>
              </div>
            ) : (
              sessions.map((session: Session) => (
                <div
                  key={session.id}
                  className={clsx(
                    "relative group w-full rounded-lg transition-colors duration-200",
                    currentSession?.id === session.id
                      ? "bg-purple-50 border border-purple-200"
                      : "hover:bg-gray-100 border border-transparent"
                  )}
                >
                  <button
                    onClick={() => selectSession(session)}
                    className="w-full px-3 py-2 text-left cursor-pointer"
                  >
                    <span className="font-medium text-gray-900 text-sm truncate block pr-8">{session.name}</span>
                  </button>
                  <button
                    onClick={(e) => handleDelete(session.id, e)}
                    className="absolute right-2 top-1/2 -translate-y-1/2 p-1.5 opacity-0 group-hover:opacity-100 hover:bg-red-50 hover:text-red-600 text-gray-400 rounded transition-all duration-200 cursor-pointer"
                    title={t('a2a.deleteSession')}
                  >
                    <Trash2 size={14} />
                  </button>
                </div>
              ))
            )}
          </div>
        </div>
      </div>

      {/* Toggle Sidebar Button */}
      <button
        onClick={() => setShowSidebar(!showSidebar)}
        className={clsx(
          "absolute top-1/2 -translate-y-1/2 z-10 p-1 bg-white border border-gray-200 rounded-r-lg text-gray-600 hover:text-gray-900 transition-colors duration-200 shadow-sm cursor-pointer",
          showSidebar ? "left-[254px]" : "left-0"
        )}
      >
        {showSidebar ? <ChevronLeft size={16} /> : <ChevronRight size={16} />}
      </button>

      {/* Main Content */}
      <div className="flex-1 flex flex-col">
        {currentSession ? (
          <>
            {/* Session Header */}
            <div className="p-4 border-b border-gray-200 bg-white">
              <div className="flex items-center justify-between">
                <div>
                  <h1 className="text-xl font-bold text-gray-900 flex items-center gap-2">
                    {currentSession.name}
                    {isRunning && (
                      <span className="flex items-center gap-1 text-xs text-purple-600 bg-purple-50 px-2 py-1 rounded-full">
                        <span className="w-2 h-2 bg-purple-600 rounded-full animate-pulse" />
                        {t('a2a.live')}
                      </span>
                    )}
                  </h1>
                  <p className="text-sm text-gray-500">
                    {currentSession.type}
                  </p>
                </div>
              </div>
            </div>

            {/* Messages Area */}
            <div
              ref={messagesContainerRef}
              onScroll={handleScroll}
              className="flex-1 overflow-y-auto p-6 space-y-6 bg-white"
            >
              {messages.length === 0 ? (
                <div className="h-full flex items-center justify-center">
                  <div className="text-center">
                    <MessageCircle size={48} className="mx-auto mb-4 text-gray-300" />
                    <h3 className="text-lg font-medium text-gray-700 mb-2">{t('a2a.startConversation')}</h3>
                    <p className="text-sm text-gray-500">{t('a2a.sendMessage')}</p>
                  </div>
                </div>
              ) : (
                <>
                  {messages.map((msg, idx) => {
                    const isHuman = msg.agentId === 'human' || msg.agentName === 'Human';
                    const isTool = msg.type === 'tool_use' || msg.type === 'tool_result';

                    return (
                      <div key={idx} className="w-full">
                        {isTool ? (
                          <div className="px-4 py-2 bg-gray-50 border-l-4 border-gray-400 rounded-r-lg ml-12 max-w-[calc(100%-3rem)]">
                            <div className="flex items-center gap-2 text-xs text-gray-700 mb-1 font-medium">
                              <Wrench size={12} />
                              <span>{msg.type === 'tool_use' ? t('a2a.toolCall') : t('a2a.toolResult')}</span>
                            </div>
                            <div className="text-xs text-gray-800 break-words overflow-x-auto">
                              <ReactMarkdown
                                remarkPlugins={[remarkGfm]}
                                components={{
                                  p: ({ children }) => <p className="mb-2 last:mb-0 text-gray-800 break-words">{children}</p>,
                                  pre: ({ children }) => <pre className="my-2 overflow-x-auto bg-gray-100 rounded p-2">{children}</pre>,
                                  code(props: unknown) {
                                    const { inline, className, children, ...rest } = props as {
                                      inline?: boolean;
                                      className?: string;
                                      children?: unknown;
                                      [key: string]: unknown;
                                    };
                                    const match = /language-(\w+)/.exec(className || '');

                                    if (!inline && match) {
                                      return (
                                        <div className="my-2 overflow-x-auto">
                                          <SyntaxHighlighter
                                            {...(rest as any)}
                                            style={oneLight}
                                            language={match[1]}
                                            PreTag="div"
                                            customStyle={{ margin: 0, fontSize: '0.75rem' }}
                                            wrapLongLines={false}
                                          >
                                            {String(children ?? '').replace(/\n$/, '')}
                                          </SyntaxHighlighter>
                                        </div>
                                      );
                                    }

                                    if (!inline) {
                                      return (
                                        <div className="my-2 overflow-x-auto">
                                          <SyntaxHighlighter
                                            {...(rest as any)}
                                            style={oneLight}
                                            language="text"
                                            PreTag="div"
                                            customStyle={{ margin: 0, fontSize: '0.75rem' }}
                                            wrapLongLines={false}
                                          >
                                            {String(children ?? '').replace(/\n$/, '')}
                                          </SyntaxHighlighter>
                                        </div>
                                      );
                                    }

                                    return (
                                      <code className="px-1 py-0.5 bg-gray-200 rounded text-gray-800 font-mono text-xs break-all">
                                        {children as any}
                                      </code>
                                    );
                                  },
                                  ul: ({ children }) => <ul className="list-disc list-outside mb-2 space-y-1 text-gray-800 ml-4">{children}</ul>,
                                  ol: ({ children }) => <ol className="list-decimal list-outside mb-2 space-y-1 text-gray-800 ml-4">{children}</ol>,
                                  li: ({ children }) => <li className="break-words">{children}</li>,
                                  h1: ({ children }) => <h1 className="text-base font-bold mb-2 text-gray-900 break-words">{children}</h1>,
                                  h2: ({ children }) => <h2 className="text-sm font-bold mb-2 text-gray-900 break-words">{children}</h2>,
                                  h3: ({ children }) => <h3 className="text-xs font-bold mb-1 text-gray-900 break-words">{children}</h3>,
                                  strong: ({ children }) => <strong className="text-gray-900 font-semibold">{children}</strong>,
                                  blockquote: ({ children }) => <blockquote className="border-l-2 border-gray-400 pl-3 my-2 italic text-gray-700 break-words">{children}</blockquote>,
                                  a: ({ href, children }) => <a href={href} className="text-purple-600 hover:underline break-all" target="_blank" rel="noopener noreferrer">{children}</a>,
                                }}
                              >
                                {msg.content}
                              </ReactMarkdown>
                            </div>
                          </div>
                        ) : (
                          <div className={clsx("flex gap-3 w-full", isHuman && "flex-row-reverse")}>
                            <div className={clsx(
                              "w-10 h-10 rounded-xl flex items-center justify-center shrink-0",
                              isHuman
                                ? "bg-gray-300"
                                : "bg-purple-600"
                            )}>
                              {isHuman ? (
                                <User size={20} className="text-white" />
                              ) : (
                                <Bot size={20} className="text-white" />
                              )}
                            </div>
                            <div className="max-w-[70%]">
                              <div className="text-xs text-gray-500 mb-1 flex items-center gap-2">
                                {msg.agentName || (isHuman ? 'You' : 'Agent')}
                                {msg.timestamp && (
                                  <span className="opacity-60">
                                    {new Date(msg.timestamp).toLocaleTimeString()}
                                  </span>
                                )}
                              </div>

                              {/* 显示步骤信息 */}
                              {msg.stepIndex !== undefined && msg.stepDescription && (
                                <div className="text-xs text-purple-700 mb-2 flex items-center gap-1">
                                  <MapPin size={12} className="shrink-0" />
                                  <span className="font-medium">步骤 {msg.stepIndex}:</span>
                                  <span className="text-gray-600">{msg.stepDescription}</span>
                                </div>
                              )}

                              {/* 显示计划信息 */}
                              {(() => {
                                // 如果是 plan 类型或 Planner 发送的消息，尝试解析 JSON
                                if (msg.type === 'plan' || msg.agentName === 'Planner') {
                                  try {
                                    // 尝试解析 content 中的 JSON
                                    const planData = JSON.parse(msg.content);
                                    if (planData.goal && planData.steps && Array.isArray(planData.steps)) {
                                      return (
                                        <div className="mb-2 px-3 py-2 bg-purple-50 border border-purple-200 rounded-lg">
                                          <div className="text-xs font-medium text-purple-900 mb-2 flex items-center gap-1">
                                            <ClipboardList size={12} className="shrink-0" />
                                            <span>{t('a2a.executionPlan') || '执行计划'}</span>
                                          </div>
                                          <div className="text-xs text-gray-800 mb-2">
                                            <strong className="text-purple-900">目标：</strong>
                                            <span className="ml-1">{planData.goal}</span>
                                          </div>
                                          <div className="text-xs text-gray-700">
                                            <strong className="text-purple-900">步骤：</strong>
                                            <ol className="list-decimal list-inside mt-1 space-y-1 ml-2">
                                              {planData.steps.map((step: any, stepIdx: number) => (
                                                <li key={stepIdx} className="text-gray-700">
                                                  <span className="font-medium">{step.instruction}</span>
                                                  {step.assignedAgentName && (
                                                    <span className="text-purple-600 text-[11px] ml-2">
                                                      - {step.assignedAgentName}
                                                    </span>
                                                  )}
                                                </li>
                                              ))}
                                            </ol>
                                          </div>
                                        </div>
                                      );
                                    }
                                  } catch (e) {
                                    // JSON 解析失败，继续检查其他情况
                                  }
                                  
                                  // 如果有单独的 goal 字段（实时流式时的情况）
                                  if (msg.goal) {
                                    return (
                                      <div className="mb-2 px-3 py-2 bg-purple-50 border border-purple-200 rounded-lg">
                                        <div className="text-xs font-medium text-purple-900 mb-1 flex items-center gap-1">
                                          <ClipboardList size={12} className="shrink-0" />
                                          <span>{t('a2a.executionPlan') || '执行计划'}</span>
                                        </div>
                                        <div className="text-xs text-gray-800">
                                          {msg.goal}
                                        </div>
                                      </div>
                                    );
                                  }
                                }
                                return null;
                              })()}

                              {/* 显示问题提示 */}
                              {msg.type === 'question' && (
                                <div className="mb-2 px-3 py-2 bg-amber-50 border border-amber-200 rounded-lg">
                                  <div className="text-xs font-medium text-amber-900 flex items-center gap-1">
                                    <AlertCircle size={12} className="shrink-0" />
                                    <span>{t('a2a.needClarification') || '需要澄清'}</span>
                                  </div>
                                </div>
                              )}

                              {msg.content && (() => {
                                // 如果是 plan 类型，检查 content 是否为 JSON 格式
                                // 如果是 JSON 且已经被上面渲染过了，就不再显示原始内容
                                if (msg.type === 'plan' || msg.agentName === 'Planner') {
                                  try {
                                    const planData = JSON.parse(msg.content);
                                    if (planData.goal && planData.steps && Array.isArray(planData.steps)) {
                                      // JSON 格式的 plan 已经在上面渲染了，不需要再显示
                                      return null;
                                    }
                                  } catch (e) {
                                    // 不是 JSON，继续正常渲染
                                  }
                                }
                                
                                return (
                                <div className={clsx(
                                  "px-4 py-3 rounded-2xl text-sm",
                                  isHuman
                                    ? "bg-slate-100 text-slate-900 rounded-tr-sm"
                                    : "bg-white border border-slate-200 text-slate-900 rounded-tl-sm shadow-sm"
                                )}>
                                  <div className="break-words max-w-full overflow-hidden">
                                    {(() => {
                                      // 判断是否为流式传输中
                                      // 只有最后一条消息才可能正在流式传输
                                      const isStreaming = isRunning && (idx === messages.length - 1);

                                      if (isStreaming) {
                                        // 流式传输中：简单剔除标签，避免不完整标签导致渲染问题
                                        const displayContent = stripArtifactTags(msg.content);
                                        return (
                                          <ReactMarkdown
                                            remarkPlugins={[remarkGfm]}
                                            components={{
                                              p: ({ children }) => <p className="mb-2 last:mb-0 break-words">{children}</p>,
                                              pre: ({ children }) => <pre className="my-2 overflow-x-auto bg-gray-200 rounded p-2 text-xs">{children}</pre>,
                                              code(props: unknown) {
                                                const { inline, className, children, ...rest } = props as {
                                                  inline?: boolean;
                                                  className?: string;
                                                  children?: unknown;
                                                  [key: string]: unknown;
                                                };
                                                const match = /language-(\w+)/.exec(className || '');
                                                const text = String(children ?? '');

                                                // 有语言标记的代码块
                                                if (!inline && match) {
                                                  return (
                                                    <div className="my-2 overflow-x-auto rounded">
                                                      <SyntaxHighlighter
                                                        {...(rest as any)}
                                                        style={oneLight}
                                                        language={match[1]}
                                                        PreTag="div"
                                                        customStyle={{ margin: 0, fontSize: '0.75rem' }}
                                                        wrapLongLines={false}
                                                      >
                                                        {text.replace(/\n$/, '')}
                                                      </SyntaxHighlighter>
                                                    </div>
                                                  );
                                                }

                                                // 无语言标记的代码块
                                                if (!inline) {
                                                  // 单行短文本按行内代码处理（修复误判）
                                                  if (!text.includes('\n') && text.length < 100) {
                                                    return (
                                                      <code className="bg-gray-300 px-1.5 py-0.5 rounded text-xs font-mono break-all text-gray-800">
                                                        {children as any}
                                                      </code>
                                                    );
                                                  }
                                                  // 多行代码块
                                                  return (
                                                    <div className="my-2 overflow-x-auto rounded">
                                                      <SyntaxHighlighter
                                                        {...(rest as any)}
                                                        style={oneLight}
                                                        language="text"
                                                        PreTag="div"
                                                        customStyle={{ margin: 0, fontSize: '0.75rem' }}
                                                        wrapLongLines={false}
                                                      >
                                                        {text.replace(/\n$/, '')}
                                                      </SyntaxHighlighter>
                                                    </div>
                                                  );
                                                }

                                                // 行内代码
                                                return (
                                                  <code className="bg-gray-300 px-1.5 py-0.5 rounded text-xs font-mono break-all text-gray-800">
                                                    {children as any}
                                                  </code>
                                                );
                                              },
                                              ul: ({ children }) => <ul className="list-disc list-outside mb-2 space-y-1 ml-4">{children}</ul>,
                                              ol: ({ children }) => <ol className="list-decimal list-outside mb-2 space-y-1 ml-4">{children}</ol>,
                                              li: ({ children }) => <li className="break-words">{children}</li>,
                                              h1: ({ children }) => <h1 className="text-lg font-bold mb-2 break-words">{children}</h1>,
                                              h2: ({ children }) => <h2 className="text-base font-bold mb-2 break-words">{children}</h2>,
                                              h3: ({ children }) => <h3 className="text-sm font-bold mb-1 break-words">{children}</h3>,
                                              blockquote: ({ children }) => <blockquote className="border-l-2 border-gray-400 pl-3 my-2 italic break-words">{children}</blockquote>,
                                              a: ({ href, children }) => <a href={href} className="text-purple-600 hover:underline break-all" target="_blank" rel="noopener noreferrer">{children}</a>,
                                              table: ({ children }) => <div className="overflow-x-auto my-2"><table className="min-w-full border-collapse">{children}</table></div>,
                                              th: ({ children }) => <th className="border border-gray-300 px-2 py-1 bg-gray-100 text-left break-words">{children}</th>,
                                              td: ({ children }) => <td className="border border-gray-300 px-2 py-1 break-words">{children}</td>
                                            }}
                                          >
                                            {displayContent}
                                          </ReactMarkdown>
                                        );
                                      } else {
                                        // 历史消息/完成的消息：解析 artifact 为卡片
                                        const parts = parseArtifacts(msg.content);
                                        return (
                                          <>
                                            {parts.map((part, partIdx) => {
                                              if (part.type === 'text') {
                                                return (
                                                  <ReactMarkdown
                                                    key={partIdx}
                                                    remarkPlugins={[remarkGfm]}
                                                    components={{
                                                      p: ({ children }) => <p className="mb-2 last:mb-0 break-words">{children}</p>,
                                                      pre: ({ children }) => <pre className="my-2 overflow-x-auto bg-gray-200 rounded p-2 text-xs">{children}</pre>,
                                                      code(props: unknown) {
                                                        const { inline, className, children, ...rest } = props as {
                                                          inline?: boolean;
                                                          className?: string;
                                                          children?: unknown;
                                                          [key: string]: unknown;
                                                        };
                                                        const match = /language-(\w+)/.exec(className || '');
                                                        const text = String(children ?? '');

                                                        // 有语言标记的代码块
                                                        if (!inline && match) {
                                                          return (
                                                            <div className="my-2 overflow-x-auto rounded">
                                                              <SyntaxHighlighter
                                                                {...(rest as any)}
                                                                style={oneLight}
                                                                language={match[1]}
                                                                PreTag="div"
                                                                customStyle={{ margin: 0, fontSize: '0.75rem' }}
                                                                wrapLongLines={false}
                                                              >
                                                                {text.replace(/\n$/, '')}
                                                              </SyntaxHighlighter>
                                                            </div>
                                                          );
                                                        }

                                                        // 无语言标记的代码块
                                                        if (!inline) {
                                                          // 单行短文本按行内代码处理（修复误判）
                                                          if (!text.includes('\n') && text.length < 100) {
                                                            return (
                                                              <code className="bg-gray-300 px-1.5 py-0.5 rounded text-xs font-mono break-all text-gray-800">
                                                                {children as any}
                                                              </code>
                                                            );
                                                          }
                                                          // 多行代码块
                                                          return (
                                                            <div className="my-2 overflow-x-auto rounded">
                                                              <SyntaxHighlighter
                                                                {...(rest as any)}
                                                                style={oneLight}
                                                                language="text"
                                                                PreTag="div"
                                                                customStyle={{ margin: 0, fontSize: '0.75rem' }}
                                                                wrapLongLines={false}
                                                              >
                                                                {text.replace(/\n$/, '')}
                                                              </SyntaxHighlighter>
                                                            </div>
                                                          );
                                                        }

                                                        // 行内代码
                                                        return (
                                                          <code className="bg-gray-300 px-1.5 py-0.5 rounded text-xs font-mono break-all text-gray-800">
                                                            {children as any}
                                                          </code>
                                                        );
                                                      },
                                                      ul: ({ children }) => <ul className="list-disc list-outside mb-2 space-y-1 ml-4">{children}</ul>,
                                                      ol: ({ children }) => <ol className="list-decimal list-outside mb-2 space-y-1 ml-4">{children}</ol>,
                                                      li: ({ children }) => <li className="break-words">{children}</li>,
                                                      h1: ({ children }) => <h1 className="text-lg font-bold mb-2 break-words">{children}</h1>,
                                                      h2: ({ children }) => <h2 className="text-base font-bold mb-2 break-words">{children}</h2>,
                                                      h3: ({ children }) => <h3 className="text-sm font-bold mb-1 break-words">{children}</h3>,
                                                      blockquote: ({ children }) => <blockquote className="border-l-2 border-gray-400 pl-3 my-2 italic break-words">{children}</blockquote>,
                                                      a: ({ href, children }) => <a href={href} className="text-purple-600 hover:underline break-all" target="_blank" rel="noopener noreferrer">{children}</a>,
                                                      table: ({ children }) => <div className="overflow-x-auto my-2"><table className="min-w-full border-collapse">{children}</table></div>,
                                                      th: ({ children }) => <th className="border border-gray-300 px-2 py-1 bg-gray-100 text-left break-words">{children}</th>,
                                                      td: ({ children }) => <td className="border border-gray-300 px-2 py-1 break-words">{children}</td>
                                                    }}
                                                  >
                                                    {part.content}
                                                  </ReactMarkdown>
                                                );
                                              } else {
                                                // Artifact 卡片
                                                return (
                                                  <ArtifactCard
                                                    key={partIdx}
                                                    artifactKey={part.key!}
                                                    content={part.content}
                                                  />
                                                );
                                              }
                                            })}
                                          </>
                                        );
                                      }
                                    })()}
                                  </div>
                                </div>
                                );
                              })()}

                              {/* Tool Calls for this step */}
                              {msg.stepId && toolCallsByStepId[msg.stepId] && toolCallsByStepId[msg.stepId].length > 0 && (
                                <ToolCallList toolCalls={toolCallsByStepId[msg.stepId]} />
                              )}
                            </div>
                          </div>
                        )}
                      </div>
                    );
                  })}
                  {isRunning && (
                    <div className="flex items-center gap-2 text-gray-600">
                      <Loader2 size={16} className="animate-spin text-purple-600" />
                      <span className="text-sm">{t('a2a.agentsThinking')}</span>
                    </div>
                  )}
                  <div ref={messagesEndRef} />
                </>
              )}
            </div>

            {/* Input Area */}
            <div className="p-4 border-t border-gray-200 bg-white">
              <div className="flex gap-3">
                <input
                  type="text"
                  value={inputMessage}
                  onChange={e => setInputMessage(e.target.value)}
                  onKeyDown={handleKeyDown}
                  placeholder={currentSession.status === 'CREATED' ? t('a2a.enterInitialTopic') : t('a2a.sendMessagePlaceholder')}
                  className="flex-1 bg-white border border-gray-300 rounded-xl px-4 py-3 text-gray-900 focus:ring-2 focus:ring-purple-500 focus:border-transparent outline-none shadow-sm"
                  disabled={isRunning}
                />
                {isRunning ? (
                  <button
                    onClick={handleStop}
                    className="px-6 py-3 bg-red-600 hover:bg-red-700 text-white rounded-xl font-medium transition-colors duration-200 flex items-center gap-2 cursor-pointer shadow-sm"
                    title={t('a2a.stopGeneration') || 'Stop Generation'}
                  >
                    <StopCircle size={20} />
                    <span className="hidden sm:inline">{t('a2a.stop') || 'Stop'}</span>
                  </button>
                ) : (
                  <button
                    onClick={handleSend}
                    disabled={!inputMessage.trim()}
                    className="px-6 py-3 bg-purple-600 hover:bg-purple-700 text-white rounded-xl font-medium transition-colors duration-200 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2 cursor-pointer shadow-sm"
                  >
                    <Send size={20} />
                  </button>
                )}
              </div>
            </div>
          </>
        ) : (
          /* Empty State */
          <div className="flex-1 flex items-center justify-center bg-white">
            <div className="text-center">
              <div className="w-20 h-20 mx-auto mb-6 rounded-2xl bg-purple-50 flex items-center justify-center">
                <Users size={40} className="text-purple-600" />
              </div>
              <h2 className="text-2xl font-bold text-gray-900 mb-2">{t('a2a.title')}</h2>
              <p className="text-gray-600 mb-6 max-w-md">
                {t('a2a.sendMessage')}
              </p>
              <button
                onClick={() => setShowCreateModal(true)}
                className="inline-flex items-center gap-2 bg-purple-600 hover:bg-purple-700 text-white px-6 py-3 rounded-xl font-medium transition-colors duration-200 cursor-pointer shadow-sm"
              >
                <Plus size={20} />
                {t('a2a.createSession')}
              </button>
            </div>
          </div>
        )}
      </div>

      {/* Create Session Modal */}
      {showCreateModal && (
        <div className="fixed inset-0 bg-black/50 backdrop-blur-sm flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-2xl w-full max-w-lg border border-gray-200 shadow-2xl">
            <div className="p-6 border-b border-gray-200 flex justify-between items-center">
              <h2 className="text-2xl font-bold text-gray-900">{t('sessionModal.title')}</h2>
              <button
                onClick={() => setShowCreateModal(false)}
                className="text-gray-600 hover:text-gray-900 transition-colors duration-200 cursor-pointer"
              >
                <X size={24} />
              </button>
            </div>

            <div className="p-6 space-y-5">
              {/* Session Name */}
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">{t('sessionModal.sessionNameLabel')} *</label>
                <input
                  type="text"
                  value={newSessionName}
                  onChange={e => setNewSessionName(e.target.value)}
                  className="w-full bg-white border border-gray-300 rounded-xl px-4 py-3 text-gray-900 focus:ring-2 focus:ring-purple-500 focus:border-transparent outline-none"
                  placeholder={t('sessionModal.sessionNamePlaceholder')}
                />
              </div>

              {/* Select Agents */}
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">
                  {t('sessionModal.selectAgentsLabel')} *
                  <span className="text-xs text-gray-500 ml-2">(Built-in Moderator included • min 1)</span>
                </label>
                <div className="grid grid-cols-2 gap-2 max-h-48 overflow-y-auto bg-gray-50 border border-gray-200 rounded-xl p-3">
                  {workerAgents.length === 0 ? (
                    <p className="text-sm text-gray-500 text-center py-4 col-span-2">
                      {t('sessionModal.noAgentsAvailable')}
                    </p>
                  ) : (
                    workerAgents.map(agent => (
                      <button
                        key={agent.id}
                        type="button"
                        onClick={() => toggleAgentSelection(agent.id)}
                        className={clsx(
                          "p-3 rounded-xl border transition-colors duration-200 text-left cursor-pointer",
                          selectedAgentIds.includes(agent.id)
                            ? "border-purple-300 bg-purple-50"
                            : "border-gray-200 hover:border-gray-300"
                        )}
                      >
                        <div className="flex items-center gap-2">
                          {selectedAgentIds.includes(agent.id) ? (
                            <CheckCircle size={16} className="text-purple-600" />
                          ) : (
                            <Bot size={16} className="text-gray-500" />
                          )}
                          <span className={clsx(
                            "text-sm font-medium truncate",
                            selectedAgentIds.includes(agent.id) ? "text-gray-900" : "text-gray-600"
                          )}>
                            {agent.name}
                          </span>
                        </div>
                      </button>
                    ))
                  )}
                </div>
              </div>
            </div>

            <div className="p-6 border-t border-gray-200 flex justify-end gap-3">
              <button
                onClick={() => setShowCreateModal(false)}
                className="px-6 py-2.5 text-gray-700 hover:text-gray-900 hover:bg-gray-100 rounded-xl transition-colors duration-200 cursor-pointer"
              >
                {t('sessionModal.cancel')}
              </button>
              <button
                onClick={handleCreate}
                disabled={isCreating || !newSessionName.trim() || selectedAgentIds.length < 1}
                className="px-6 py-2.5 bg-purple-600 hover:bg-purple-700 text-white rounded-xl transition-colors duration-200 font-medium disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2 cursor-pointer shadow-sm"
              >
                {isCreating && <Loader2 size={16} className="animate-spin" />}
                {t('sessionModal.create')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
