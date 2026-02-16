import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useTeamStore } from '../../team/model/store';
import type { LeadMessage } from '../../team/model/store';
import { Send, Bot, User, Loader2, Users, AlertCircle, StopCircle, Wrench, CheckCircle2, ChevronDown } from 'lucide-react';
import { clsx } from 'clsx';
import ReactMarkdown from 'react-markdown';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneLight } from 'react-syntax-highlighter/dist/esm/styles/prism';
import remarkGfm from 'remark-gfm';
import { parseThinkContent } from '../../team/ui/thinkParser';
import { useTranslation } from 'react-i18next';
import { getToolDisplayName } from '../../../shared/utils/toolName';

export const LeadChatPanel: React.FC = () => {
  const { t } = useTranslation();
  const { messages, sendToLead, stopTeam, leadStatus, leadLifecycleStatus, teamPhase, isTeamStarted } = useTeamStore();
  const [inputMessage, setInputMessage] = useState('');
  const [isStopping, setIsStopping] = useState(false);
  const [isUserScrolling, setIsUserScrolling] = useState(false);

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const messagesContainerRef = useRef<HTMLDivElement>(null);
  const lastScrollTopRef = useRef<number>(0);

  // 自动滚动到底部
  useEffect(() => {
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

    // 检测用户是否主动向上滚动
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

  const handleSend = async () => {
    if (!inputMessage.trim()) return;
    const msg = inputMessage;
    setInputMessage('');
    await sendToLead(msg);
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleStop = async () => {
    if (isStopping) return;
    setIsStopping(true);
    try {
      await stopTeam();
    } finally {
      setIsStopping(false);
    }
  };

  const lifecycleLabel = (status: 'RUNNING' | 'STOPPED' | 'DESTROYED') => {
    if (status === 'RUNNING') return t('team.lifecycleRunning');
    if (status === 'DESTROYED') return t('team.lifecycleDestroyed');
    return t('team.lifecycleStopped');
  };

  const lifecycleDotClass = (status: 'RUNNING' | 'STOPPED' | 'DESTROYED') => {
    if (status === 'RUNNING') return 'bg-emerald-500';
    if (status === 'DESTROYED') return 'bg-gray-500';
    return 'bg-amber-500';
  };

  const lifecycleBadgeClass = (status: 'RUNNING' | 'STOPPED' | 'DESTROYED') => {
    if (status === 'RUNNING') return 'bg-emerald-50 text-emerald-700 border-emerald-200';
    if (status === 'DESTROYED') return 'bg-gray-100 text-gray-700 border-gray-300';
    return 'bg-amber-50 text-amber-700 border-amber-200';
  };

  // 只要团队处于运行过程（包括等待/执行/收尾），都允许显示 Stop。
  const showStopButton = (
    isTeamStarted
    || teamPhase === 'active'
    || teamPhase === 'shutting_down'
    || leadLifecycleStatus === 'RUNNING'
    || messages.length > 0
  )
    && leadLifecycleStatus !== 'DESTROYED';
  const showWorkingIndicator = leadStatus === 'active' || leadLifecycleStatus === 'RUNNING';

  return (
    <div className="flex flex-col h-full bg-white">
      {/* Header */}
      <div className="px-6 py-4 border-b border-gray-200 bg-white">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-purple-600 flex items-center justify-center">
              <Bot size={20} className="text-white" />
            </div>
            <div>
              <h2 className="text-lg font-semibold text-gray-900">{t('team.leadTitle')}</h2>
              <div className="text-sm text-gray-500">{t('team.leadCoordinator')}</div>
            </div>
          </div>
          <div className={clsx(
            "inline-flex items-center gap-1 px-2.5 py-1 rounded-lg text-xs border font-medium whitespace-nowrap",
            lifecycleBadgeClass(leadLifecycleStatus)
          )}>
            <span className={clsx("inline-block w-1.5 h-1.5 rounded-full", lifecycleDotClass(leadLifecycleStatus))} />
            <span>{t('team.leadLifecycle', { status: lifecycleLabel(leadLifecycleStatus) })}</span>
          </div>
        </div>
      </div>

      {/* Messages Area */}
      <div
        ref={messagesContainerRef}
        onScroll={handleScroll}
        className="flex-1 overflow-y-auto p-6 space-y-4 bg-gray-50"
      >
        {messages.length === 0 ? (
          <div className="h-full flex items-center justify-center">
            <div className="text-center">
              <Users size={48} className="mx-auto mb-4 text-gray-300" />
              <h3 className="text-lg font-medium text-gray-700 mb-2">{t('team.teamChatTitle')}</h3>
              <p className="text-sm text-gray-500">{t('team.teamChatHint')}</p>
            </div>
          </div>
        ) : (
          <>
            {messages.map((msg, idx) => {
              const isUser = msg.type === 'user';
              const isSystem = msg.type === 'system';
              const isWorker = msg.type === 'worker';
              const isToolCall = msg.type === 'tool_call';
              const isToolResult = msg.type === 'tool_result';

              return (
                <div key={msg.id || idx} className="w-full">
                  {isSystem ? (
                    // System message
                    <div className="flex justify-center">
                      <div className="px-4 py-2 bg-blue-50 border border-blue-200 rounded-lg text-sm text-blue-800 flex items-center gap-2 max-w-[80%]">
                        <AlertCircle size={14} className="shrink-0" />
                        <span>{msg.content}</span>
                      </div>
                    </div>
                  ) : (
                    // User, Lead, or Worker message
                    <div className={clsx("flex gap-3 w-full", isUser && "flex-row-reverse")}>
                      <div className={clsx(
                        "w-10 h-10 rounded-xl flex items-center justify-center shrink-0",
                        isUser ? "bg-gray-300" : isWorker ? "bg-green-600" : "bg-purple-600"
                      )}>
                        {isUser ? (
                          <User size={20} className="text-white" />
                        ) : (
                          <Bot size={20} className="text-white" />
                        )}
                      </div>
                      <div className={clsx("max-w-[70%]", (isToolCall || isToolResult) && "max-w-[88%]")}>
                        <div className="text-xs text-gray-500 mb-1 flex items-center gap-2">
                          {msg.agentName || (isUser ? t('team.you') : isWorker ? t('team.worker') : t('team.lead'))}
                          {msg.timestamp && (
                            <span className="opacity-60">
                              {new Date(msg.timestamp).toLocaleTimeString()}
                            </span>
                          )}
                        </div>
                        {(isToolCall || isToolResult) ? (
                          <LeadToolCallBubble message={msg} />
                        ) : (
                          <div className={clsx(
                            "px-4 py-3 rounded-2xl text-sm",
                            isUser
                              ? "bg-slate-100 text-slate-900 rounded-tr-sm"
                              : "bg-white border border-slate-200 text-slate-900 rounded-tl-sm shadow-sm"
                          )}>
                            <ThinkAwareMarkdownContent content={msg.content} />
                          </div>
                        )}
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
            {showWorkingIndicator && (
              <div className="flex items-center gap-2 text-gray-600">
                <Loader2 size={16} className="animate-spin text-purple-600" />
                <span className="text-sm">{t('team.teamWorking')}</span>
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
            placeholder={t('team.sendToLeadPlaceholder')}
            className="flex-1 bg-white border border-gray-300 rounded-xl px-4 py-3 text-gray-900 focus:ring-2 focus:ring-purple-500 focus:border-transparent outline-none shadow-sm"
          />
          {showStopButton && (
            <button
              onClick={handleStop}
              disabled={isStopping}
              className="px-6 py-3 bg-red-600 hover:bg-red-700 text-white rounded-xl font-medium transition-colors duration-200 flex items-center gap-2 cursor-pointer shadow-sm disabled:opacity-50 disabled:cursor-not-allowed"
              title={t('team.stopExecution')}
            >
              {isStopping ? <Loader2 size={20} className="animate-spin" /> : <StopCircle size={20} />}
              <span className="hidden sm:inline">{isStopping ? t('team.stopping') : t('team.stop')}</span>
            </button>
          )}
          <button
            onClick={handleSend}
            disabled={!inputMessage.trim() || isStopping}
            className="px-6 py-3 bg-purple-600 hover:bg-purple-700 text-white rounded-xl font-medium transition-colors duration-200 flex items-center gap-2 cursor-pointer shadow-sm disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <Send size={20} />
          </button>
        </div>
      </div>
    </div>
  );
};

const LeadToolCallBubble: React.FC<{ message: LeadMessage }> = ({ message }) => {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);

  const status = message.toolStatus || (message.type === 'tool_result' ? 'SUCCESS' : 'RUNNING');
  const statusLabel = status === 'FAILED'
    ? t('team.toolStatusFailed')
    : status === 'SUCCESS'
      ? t('team.toolStatusSuccess')
      : t('team.toolStatusRunning');
  const statusIcon = status === 'FAILED'
    ? <AlertCircle size={14} className="text-red-600" />
    : status === 'SUCCESS'
      ? <CheckCircle2 size={14} className="text-green-600" />
      : <Loader2 size={14} className="animate-spin text-blue-600" />;

  const cardStyle = status === 'FAILED'
    ? 'border-red-200 bg-red-50'
    : status === 'SUCCESS'
      ? 'border-purple-200 bg-purple-50'
      : 'border-amber-200 bg-amber-50';

  const displayToolName = getToolDisplayName(message.toolName, t('team.tool'));

  const argsText = formatJsonString(message.toolArgs || (message.type === 'tool_call' ? message.content : ''));
  const resultText = formatJsonString(message.toolResult || (message.type === 'tool_result' ? message.content : ''));

  return (
    <div className={clsx("rounded-xl border px-3 py-2 text-sm shadow-sm", cardStyle)}>
      <button
        className="w-full flex items-center gap-2 cursor-pointer"
        onClick={() => setExpanded(v => !v)}
      >
        <Wrench size={14} className="text-gray-600 shrink-0" />
        <span className="text-sm font-semibold text-gray-700 truncate">{displayToolName}</span>
        <span className="ml-auto text-xs text-gray-400">{new Date(message.timestamp).toLocaleTimeString()}</span>
        <span className="ml-1">{statusIcon}</span>
        <span className={clsx(
          "text-[11px] px-2 py-0.5 rounded-full font-medium",
          status === 'FAILED' ? 'bg-red-100 text-red-700' :
          status === 'SUCCESS' ? 'bg-purple-100 text-purple-700' :
          'bg-amber-100 text-amber-700'
        )}>
          {statusLabel}
        </span>
        <ChevronDown size={14} className={clsx("text-gray-500 transition-transform", expanded && "rotate-180")} />
      </button>

      {expanded && (
        <div className="mt-2 border-t border-gray-200/70 pt-2 space-y-2">
          {argsText && (
            <div>
              <div className="text-xs text-gray-500 mb-1">{t('team.toolArgs')}</div>
              <pre className="bg-white/80 rounded-md p-2 text-xs text-gray-700 whitespace-pre-wrap break-all max-h-32 overflow-auto">
                {argsText}
              </pre>
            </div>
          )}

          {(resultText || message.errorMessage) && (
            <div>
              <div className="text-xs text-gray-500 mb-1">{t('team.toolOutput')}</div>
              <pre className={clsx(
                "rounded-md p-2 text-xs whitespace-pre-wrap break-all max-h-56 overflow-auto",
                message.errorMessage ? "bg-red-50 text-red-700" : "bg-white/80 text-gray-700"
              )}>
                {message.errorMessage || resultText}
              </pre>
            </div>
          )}

          {typeof message.durationMs === 'number' && (
            <div className="text-xs text-gray-500">
              {t('team.toolDuration')}: {message.durationMs}ms
            </div>
          )}
        </div>
      )}
    </div>
  );
};

const ThinkAwareMarkdownContent: React.FC<{ content: string }> = ({ content }) => {
  const { t } = useTranslation();
  const parsed = useMemo(() => parseThinkContent(content), [content]);
  const hasThinking = parsed.thoughts.length > 0 || parsed.hasUnclosedThink;

  return (
    <div className="space-y-2">
      {hasThinking && (
        <ThinkBlock thoughts={parsed.thoughts} isRunning={parsed.hasUnclosedThink} />
      )}
      {parsed.visibleContent ? (
        <LeadMarkdownContent content={parsed.visibleContent} />
      ) : (
        hasThinking && (
          <div className="text-xs text-slate-500">{t('team.thinkingOnly')}</div>
        )
      )}
    </div>
  );
};

const ThinkBlock: React.FC<{ thoughts: string[]; isRunning: boolean }> = ({ thoughts, isRunning }) => {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  const content = thoughts.join('\n\n').trim();
  const title = isRunning ? t('team.thinking') : t('team.thoughtProcess');

  return (
    <div className="rounded-xl border border-indigo-200 bg-indigo-50/80">
      <button
        type="button"
        className="w-full flex items-center gap-2 px-3 py-2 text-left cursor-pointer"
        onClick={() => setExpanded(value => !value)}
      >
        <span className="text-xs font-semibold text-indigo-700">{title}</span>
        {isRunning && <Loader2 size={12} className="animate-spin text-indigo-600" />}
        <span className="text-[11px] text-indigo-600 ml-auto">
          {expanded ? t('team.collapse') : t('team.expand')}
        </span>
        <ChevronDown size={14} className={clsx("text-indigo-500 transition-transform", expanded && "rotate-180")} />
      </button>

      {expanded && (
        <pre className="border-t border-indigo-200/80 px-3 py-2 text-xs text-indigo-900 whitespace-pre-wrap break-words max-h-52 overflow-auto">
          {content || '...'}
        </pre>
      )}
    </div>
  );
};

const LeadMarkdownContent: React.FC<{ content: string }> = ({ content }) => {
  return (
    <div className="break-words max-w-full overflow-hidden">
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

            if (!inline) {
              if (!text.includes('\n') && text.length < 100) {
                return (
                  <code className="bg-gray-300 px-1.5 py-0.5 rounded text-xs font-mono break-all text-gray-800">
                    {children as any}
                  </code>
                );
              }
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
        {content}
      </ReactMarkdown>
    </div>
  );
};

const formatJsonString = (value: string): string => {
  if (!value) return '';
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
};
