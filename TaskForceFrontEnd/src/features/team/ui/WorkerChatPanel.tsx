import React, { useState, useRef, useEffect, useMemo } from 'react';
import { Send, Loader2, Wrench, CheckCircle2, AlertCircle, ChevronDown } from 'lucide-react';
import { clsx } from 'clsx';
import { useTeamStore } from '../model/store';
import type { WorkerMessage } from '../model/store';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneLight } from 'react-syntax-highlighter/dist/esm/styles/prism';

export const WorkerChatPanel: React.FC = () => {
  const { activeWorkerId, workerMessages, members, sendToWorker } = useTeamStore();
  const [input, setInput] = useState('');
  const [isSending, setIsSending] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);

  const messages = useMemo(
    () => (activeWorkerId ? workerMessages[activeWorkerId] || [] : []),
    [activeWorkerId, workerMessages]
  );
  const activeMember = members.find(m => m.instanceId === activeWorkerId);

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
        <p className="text-sm">选择一个 Worker 查看对话</p>
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
            activeMember?.status === 'BUSY' ? "bg-blue-500" :
            activeMember?.status === 'ERROR' ? "bg-red-500" :
            activeMember?.status === 'STOPPED' ? "bg-gray-400" :
            "bg-green-500"
          )} />
          <h3 className="font-semibold text-gray-900">{activeMember?.agentName || 'Worker'}</h3>
          <span className="text-xs text-gray-400 ml-auto">{activeMember?.status || 'UNKNOWN'}</span>
        </div>
      </div>

      {/* Messages */}
      <div className="flex-1 overflow-y-auto p-4 space-y-3">
        {messages.length === 0 ? (
          <div className="h-full flex items-center justify-center text-gray-400">
            <p className="text-sm">暂无消息</p>
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
            placeholder="发送消息给 Worker..."
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
              {message.type === 'thinking' ? '💭 思考中' :
               message.type === 'tool_call' ? `🔧 ${message.toolName || 'Tool Call'}` :
               message.type === 'tool_result' ? `✅ ${message.toolName || 'Tool Result'}` :
               message.type === 'output' ? '📤 输出' :
               message.type === 'error' ? '❌ 错误' :
               message.type === 'system' ? '🔔 系统' : ''}
            </span>
            <span className="text-xs text-gray-400 ml-auto">
              {new Date(message.timestamp).toLocaleTimeString()}
            </span>
          </div>
        )}

        {/* Content */}
        {message.type === 'output' ? (
          <MarkdownContent content={message.content} />
        ) : (
          <pre className="whitespace-pre-wrap break-words font-sans">
            {message.content}
          </pre>
        )}

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
  const [expanded, setExpanded] = useState(false);

  const status = message.toolStatus || (message.type === 'tool_result' ? 'SUCCESS' : 'RUNNING');
  const statusLabel = status === 'FAILED' ? '失败' : status === 'SUCCESS' ? '完成' : '执行中';
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
    ? `${message.serverName}::${message.toolName || 'Tool'}`
    : (message.toolName || 'Tool');

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
                <div className="text-xs text-gray-500 mb-1">参数</div>
                <pre className="bg-white/80 rounded-md p-2 text-xs text-gray-700 whitespace-pre-wrap break-all max-h-32 overflow-auto">
                  {argsText}
                </pre>
              </div>
            )}

            {(resultText || message.errorMessage) && (
              <div>
                <div className="text-xs text-gray-500 mb-1">结果</div>
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
                耗时: {message.durationMs}ms
              </div>
            )}
          </div>
        )}
      </div>
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
