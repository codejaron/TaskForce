import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Send, Loader2, Wrench, CheckCircle2, AlertCircle, ChevronDown } from 'lucide-react';
import { clsx } from 'clsx';
import { useTeamStore } from '../model/store';
import type { WorkerMessage } from '../model/store';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneLight } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { parseThinkContent } from './thinkParser';
import { useTranslation } from 'react-i18next';

export const WorkerChatPanel: React.FC = () => {
  const { t } = useTranslation();
  const { activeWorkerId, workerMessages, members, sendToWorker } = useTeamStore();
  const [input, setInput] = useState('');
  const [isSending, setIsSending] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);

  const messages = useMemo(
    () => (activeWorkerId ? workerMessages[activeWorkerId] || [] : []),
    [activeWorkerId, workerMessages]
  );
  const activeMember = members.find(m => m.instanceId === activeWorkerId);

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

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const handleSend = async () => {
    if (!input.trim() || !activeWorkerId || isSending) return;

    const message = input.trim();
    setInput('');
    setIsSending(true);

    try {
      await sendToWorker(activeWorkerId, message);
    } finally {
      setIsSending(false);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      handleSend();
    }
  };

  if (!activeWorkerId || !activeMember) {
    return (
      <div className="h-full flex items-center justify-center text-gray-500">
        <p className="text-sm">{t('team.selectWorkerToView')}</p>
      </div>
    );
  }

  return (
    <div className="h-full flex flex-col">
      {/* Header */}
      <div className="flex-shrink-0 px-4 py-3 border-b border-gray-200 bg-white">
        <div className="flex items-center gap-2">
          <span className={clsx(
            "w-2 h-2 rounded-full",
            lifecycleDotClass(activeMember?.lifecycleStatus || 'STOPPED')
          )} />
          <h3 className="font-semibold text-gray-900">{activeMember?.agentName || t('team.workerDefaultName')}</h3>
          <span className="text-xs text-gray-500 ml-auto">{lifecycleLabel(activeMember?.lifecycleStatus || 'STOPPED')}</span>
        </div>
      </div>

      {/* Messages */}
      <div className="flex-1 overflow-y-auto p-4 space-y-3">
        {messages.length === 0 ? (
          <div className="h-full flex items-center justify-center text-gray-400">
            <p className="text-sm">{t('team.noMessages')}</p>
          </div>
        ) : (
          messages.map(msg => (
            <MessageBubble key={msg.id} message={msg} />
          ))
        )}
        <div ref={messagesEndRef} />
      </div>

      {/* Input */}
      <div className="flex-shrink-0 p-4 border-t border-gray-200 bg-white">
        <div className="flex gap-3">
          <input
            type="text"
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={t('team.sendToWorkerPlaceholder')}
            className="flex-1 bg-white border border-gray-300 rounded-xl px-4 py-3 text-gray-900 focus:ring-2 focus:ring-green-500 focus:border-transparent outline-none shadow-sm"
            disabled={isSending}
          />
          <button
            onClick={handleSend}
            disabled={!input.trim() || isSending}
            className="px-6 py-3 bg-green-600 hover:bg-green-700 text-white rounded-xl font-medium transition-colors duration-200 flex items-center gap-2 cursor-pointer shadow-sm disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {isSending ? <Loader2 size={20} className="animate-spin" /> : <Send size={20} />}
          </button>
        </div>
      </div>
    </div>
  );
};

const MessageBubble: React.FC<{ message: WorkerMessage }> = ({ message }) => {
  const { t } = useTranslation();
  const isUser = message.type === 'user';
  const isToolMessage = message.type === 'tool_call' || message.type === 'tool_result';

  if (isToolMessage) {
    return <ToolCallBubble message={message} />;
  }

  return (
    <div className={clsx(
      "flex",
      isUser ? "justify-end" : "justify-start"
    )}>
      <div className={clsx(
        "max-w-[80%] rounded-lg px-3 py-2 text-sm",
        isUser ? "bg-green-600 text-white" :
        message.type === 'thinking' ? "bg-blue-50 border border-blue-200 text-gray-700" :
        message.type === 'tool_call' ? "bg-amber-50 border border-amber-200 text-gray-700" :
        message.type === 'tool_result' ? "bg-purple-50 border border-purple-200 text-gray-700" :
        message.type === 'output' ? "bg-green-50 border border-green-200 text-gray-700" :
        message.type === 'error' ? "bg-red-50 border border-red-200 text-red-700" :
        "bg-gray-50 border border-gray-200 text-gray-700"
      )}>
        {/* Type Label */}
        {!isUser && (
          <div className="flex items-center gap-1 mb-1">
            <span className="text-xs font-medium text-gray-500">
              {message.type === 'thinking' ? `💭 ${t('team.msgTypeThinking')}` :
               message.type === 'tool_call' ? `🔧 ${message.toolName || t('team.msgTypeToolCall')}` :
               message.type === 'tool_result' ? `✅ ${message.toolName || t('team.msgTypeToolResult')}` :
               message.type === 'output' ? `📤 ${t('team.msgTypeOutput')}` :
               message.type === 'error' ? `❌ ${t('team.msgTypeError')}` :
               message.type === 'system' ? `🔔 ${t('team.msgTypeSystem')}` : ''}
            </span>
            <span className="text-xs text-gray-400 ml-auto">
              {new Date(message.timestamp).toLocaleTimeString()}
            </span>
          </div>
        )}

        {/* Content */}
        <ThinkAwareWorkerContent
          content={message.content}
          markdown={message.type === 'output'}
        />

        {/* Timestamp for user messages */}
        {isUser && (
          <div className="text-xs text-green-100 mt-1 text-right">
            {new Date(message.timestamp).toLocaleTimeString()}
          </div>
        )}
      </div>
    </div>
  );
};

const ToolCallBubble: React.FC<{ message: WorkerMessage }> = ({ message }) => {
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

  const displayToolName = message.serverName
    ? `${message.serverName}::${message.toolName || t('team.tool')}`
    : (message.toolName || t('team.tool'));

  const argsText = formatJsonString(message.toolArgs || (message.type === 'tool_call' ? message.content : ''));
  const resultText = formatJsonString(message.toolResult || (message.type === 'tool_result' ? message.content : ''));

  return (
    <div className="flex justify-start">
      <div className={clsx("max-w-[88%] rounded-xl border px-3 py-2 text-sm shadow-sm", cardStyle)}>
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
    </div>
  );
};

const ThinkAwareWorkerContent: React.FC<{ content: string; markdown: boolean }> = ({ content, markdown }) => {
  const { t } = useTranslation();
  const parsed = useMemo(() => parseThinkContent(content), [content]);
  const hasThinking = parsed.thoughts.length > 0 || parsed.hasUnclosedThink;

  return (
    <div className="space-y-2">
      {hasThinking && (
        <ThinkBlock thoughts={parsed.thoughts} isRunning={parsed.hasUnclosedThink} />
      )}
      {parsed.visibleContent ? (
        markdown ? (
          <MarkdownContent content={parsed.visibleContent} />
        ) : (
          <pre className="whitespace-pre-wrap break-words font-sans">
            {parsed.visibleContent}
          </pre>
        )
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
    <div className="rounded-xl border border-sky-200 bg-sky-50/80">
      <button
        type="button"
        className="w-full flex items-center gap-2 px-3 py-2 text-left cursor-pointer"
        onClick={() => setExpanded(value => !value)}
      >
        <span className="text-xs font-semibold text-sky-700">{title}</span>
        {isRunning && <Loader2 size={12} className="animate-spin text-sky-600" />}
        <span className="text-[11px] text-sky-600 ml-auto">
          {expanded ? t('team.collapse') : t('team.expand')}
        </span>
        <ChevronDown size={14} className={clsx("text-sky-500 transition-transform", expanded && "rotate-180")} />
      </button>

      {expanded && (
        <pre className="border-t border-sky-200/80 px-3 py-2 text-xs text-sky-900 whitespace-pre-wrap break-words max-h-52 overflow-auto">
          {content || '...'}
        </pre>
      )}
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

const MarkdownContent: React.FC<{ content: string }> = ({ content }) => {
  return (
    <div className="break-words max-w-full overflow-hidden">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          p: ({ children }) => <p className="mb-2 last:mb-0 break-words">{children}</p>,
          pre: ({ children }) => <pre className="my-2 overflow-x-auto bg-gray-200 rounded p-2 text-xs">{children}</pre>,
          code(props: unknown) {
            const { inline, className, children } = props as {
              inline?: boolean;
              className?: string;
              children?: unknown;
            };
            const match = /language-(\w+)/.exec(className || '');
            const text = String(children ?? '');

            if (!inline && match) {
              return (
                <div className="my-2 overflow-x-auto rounded">
                  <SyntaxHighlighter
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
                    {text}
                  </code>
                );
              }
              return (
                <div className="my-2 overflow-x-auto rounded">
                  <SyntaxHighlighter
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
                {text}
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
