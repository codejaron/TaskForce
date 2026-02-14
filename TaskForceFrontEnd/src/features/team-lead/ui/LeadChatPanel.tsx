import React, { useEffect, useState, useRef } from 'react';
import { useTeamStore } from '../../team/model/store';
import { Send, Bot, User, Loader2, Users, AlertCircle, StopCircle } from 'lucide-react';
import { clsx } from 'clsx';
import ReactMarkdown from 'react-markdown';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneLight } from 'react-syntax-highlighter/dist/esm/styles/prism';
import remarkGfm from 'remark-gfm';

export const LeadChatPanel: React.FC = () => {
  const { messages, sendToLead, stopTeam, isConnected, leadStatus } = useTeamStore();
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

  const isActive = leadStatus === 'active';

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
              <h2 className="text-lg font-semibold text-gray-900">Team Lead</h2>
              <div className="flex items-center gap-2 text-sm text-gray-500">
                {isConnected ? (
                  <>
                    <span className="w-2 h-2 bg-green-500 rounded-full" />
                    <span>Connected</span>
                  </>
                ) : (
                  <>
                    <span className="w-2 h-2 bg-gray-400 rounded-full" />
                    <span>Disconnected</span>
                  </>
                )}
              </div>
            </div>
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
              <h3 className="text-lg font-medium text-gray-700 mb-2">Team Chat</h3>
              <p className="text-sm text-gray-500">Messages from the team lead will appear here</p>
            </div>
          </div>
        ) : (
          <>
            {messages.map((msg, idx) => {
              const isUser = msg.type === 'user';
              const isSystem = msg.type === 'system';
              const isWorker = msg.type === 'worker';

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
                      <div className="max-w-[70%]">
                        <div className="text-xs text-gray-500 mb-1 flex items-center gap-2">
                          {msg.agentName || (isUser ? 'You' : isWorker ? 'Worker' : 'Lead')}
                          {msg.timestamp && (
                            <span className="opacity-60">
                              {new Date(msg.timestamp).toLocaleTimeString()}
                            </span>
                          )}
                        </div>
                        <div className={clsx(
                          "px-4 py-3 rounded-2xl text-sm",
                          isUser
                            ? "bg-slate-100 text-slate-900 rounded-tr-sm"
                            : "bg-white border border-slate-200 text-slate-900 rounded-tl-sm shadow-sm"
                        )}>
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
                              {msg.content}
                            </ReactMarkdown>
                          </div>
                        </div>
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
            {isActive && (
              <div className="flex items-center gap-2 text-gray-600">
                <Loader2 size={16} className="animate-spin text-purple-600" />
                <span className="text-sm">Team is working...</span>
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
            placeholder="Send a message to the team lead..."
            className="flex-1 bg-white border border-gray-300 rounded-xl px-4 py-3 text-gray-900 focus:ring-2 focus:ring-purple-500 focus:border-transparent outline-none shadow-sm"
          />
          {isActive && (
            <button
              onClick={handleStop}
              disabled={isStopping}
              className="px-6 py-3 bg-red-600 hover:bg-red-700 text-white rounded-xl font-medium transition-colors duration-200 flex items-center gap-2 cursor-pointer shadow-sm disabled:opacity-50 disabled:cursor-not-allowed"
              title="Stop current team execution"
            >
              {isStopping ? <Loader2 size={20} className="animate-spin" /> : <StopCircle size={20} />}
              <span className="hidden sm:inline">{isStopping ? 'Stopping...' : 'Stop'}</span>
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
