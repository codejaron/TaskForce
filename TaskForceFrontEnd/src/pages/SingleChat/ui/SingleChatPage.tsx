import React, { useEffect, useState, useRef } from 'react';
import { useSingleChatStore } from '../../../features/singleChat/model/store';
import { useAgentStore } from '../../../features/agents/model/store';
import { api } from '../../../shared/api';
import { useTranslation } from 'react-i18next';
import { ToolCallList } from '../../../components/ToolCallCard';
import {
  Plus,
  MessageCircle,
  Send,
  Bot,
  User,
  X,
  ChevronLeft,
  ChevronRight,
  Loader2,
  Trash2
} from 'lucide-react';
import { clsx } from 'clsx';
import ReactMarkdown from 'react-markdown';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneLight } from 'react-syntax-highlighter/dist/esm/styles/prism';
import remarkGfm from 'remark-gfm';
import type { Session } from '../../../shared/api/types';

export const SingleChatPage: React.FC = () => {
  const { sessions, currentSession, messages, toolCalls, isStreaming, fetchSessions, selectSession, sendMessage, disconnectStream, deleteSession } = useSingleChatStore();
  const { agents, fetchAgents } = useAgentStore();
  const { t } = useTranslation();

  const [showCreateModal, setShowCreateModal] = useState(false);
  const [newSessionName, setNewSessionName] = useState('');
  const [selectedAgentId, setSelectedAgentId] = useState<string>('');
  const [isCreating, setIsCreating] = useState(false);

  const [inputMessage, setInputMessage] = useState('');
  const [showSidebar, setShowSidebar] = useState(true);

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const messagesContainerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    fetchSessions();
    fetchAgents();

    return () => {
      disconnectStream();
    };
  }, []);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const handleCreate = async () => {
    if (!newSessionName || !selectedAgentId) {
      alert('Please enter a session name and select an agent');
      return;
    }

    setIsCreating(true);
    try {
      const session = await api.sessions.create({
        name: newSessionName,
        type: 'CHAT',
        agentIds: [parseInt(selectedAgentId)]
      });
      await fetchSessions();
      selectSession(session);
      setShowCreateModal(false);
      setNewSessionName('');
      setSelectedAgentId('');
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
    sendMessage(currentSession.id, msg);
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleDelete = async (sessionId: string, e: React.MouseEvent) => {
    e.stopPropagation();

    if (window.confirm(t('singleChat.deleteMessage'))) {
      try {
        await deleteSession(sessionId);
      } catch (error) {
        console.error('Failed to delete session:', error);
        alert('Failed to delete session');
      }
    }
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
                <MessageCircle size={20} className="text-blue-600" />
                <h2 className="font-bold text-gray-900">{t('singleChat.sessions')}</h2>
              </div>
              <button
                onClick={() => setShowCreateModal(true)}
                className="p-2 bg-blue-50 hover:bg-blue-100 text-blue-600 rounded-lg transition-colors duration-200 cursor-pointer"
              >
                <Plus size={18} />
              </button>
            </div>
          </div>

          {/* Sessions List */}
          <div className="flex-1 overflow-y-auto p-3 space-y-2">
            {sessions.length === 0 ? (
              <div className="text-center py-8">
                <MessageCircle size={32} className="mx-auto mb-3 text-gray-400" />
                <p className="text-sm text-gray-500">{t('singleChat.noSessions')}</p>
                <button
                  onClick={() => setShowCreateModal(true)}
                  className="mt-3 text-sm text-blue-600 hover:text-blue-700 cursor-pointer"
                >
                  {t('singleChat.createFirst')}
                </button>
              </div>
            ) : (
              sessions.map((session: Session) => (
                <div
                  key={session.id}
                  className={clsx(
                    "relative group w-full rounded-lg transition-colors duration-200",
                    currentSession?.id === session.id
                      ? "bg-blue-50 border border-blue-200"
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
                    title={t('singleChat.deleteSession')}
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
                    {isStreaming && (
                      <span className="flex items-center gap-1 text-xs text-blue-600 bg-blue-50 px-2 py-1 rounded-full">
                        <span className="w-2 h-2 bg-blue-600 rounded-full animate-pulse" />
                        {t('singleChat.live')}
                      </span>
                    )}
                  </h1>
                  <p className="text-sm text-gray-500">
                    {t('singleChat.chatType')}
                  </p>
                </div>
              </div>
            </div>

            {/* Messages Area */}
            <div
              ref={messagesContainerRef}
              className="flex-1 overflow-y-auto p-6 space-y-6 bg-white"
            >
              {messages.length === 0 ? (
                <div className="h-full flex items-center justify-center">
                  <div className="text-center">
                    <MessageCircle size={48} className="mx-auto mb-4 text-gray-300" />
                    <h3 className="text-lg font-medium text-gray-700 mb-2">{t('singleChat.startConversation')}</h3>
                    <p className="text-sm text-gray-500">{t('singleChat.sendMessage')}</p>
                  </div>
                </div>
              ) : (
                <>
                  {messages.map((msg, idx) => {
                    const isHuman = msg.agentId === 'human' || msg.agentName === 'Human';

                    return (
                      <div key={idx} className="w-full">
                        <div className={clsx("flex gap-3 w-full", isHuman && "flex-row-reverse")}>
                          <div className={clsx(
                            "w-10 h-10 rounded-xl flex items-center justify-center shrink-0",
                            isHuman
                              ? "bg-gray-300"
                              : "bg-blue-600"
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

                            {msg.content && (
                              <div className={clsx(
                                "px-4 py-3 rounded-2xl text-sm",
                                isHuman
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
                                      a: ({ href, children }) => <a href={href} className="text-blue-600 hover:underline break-all" target="_blank" rel="noopener noreferrer">{children}</a>,
                                      table: ({ children }) => <div className="overflow-x-auto my-2"><table className="min-w-full border-collapse">{children}</table></div>,
                                      th: ({ children }) => <th className="border border-gray-300 px-2 py-1 bg-gray-100 text-left break-words">{children}</th>,
                                      td: ({ children }) => <td className="border border-gray-300 px-2 py-1 break-words">{children}</td>
                                    }}
                                  >
                                    {msg.content}
                                  </ReactMarkdown>
                                </div>
                              </div>
                            )}

                            {/* Tool Calls - 在最后一条 assistant 消息下方显示所有工具调用 */}
                            {!isHuman && idx === messages.length - 1 && toolCalls.length > 0 && (
                              <ToolCallList toolCalls={toolCalls} />
                            )}
                          </div>
                        </div>
                      </div>
                    );
                  })}
                  {isStreaming && (
                    <div className="flex items-center gap-2 text-gray-600">
                      <Loader2 size={16} className="animate-spin text-blue-600" />
                      <span className="text-sm">{t('singleChat.agentThinking')}</span>
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
                  placeholder={t('singleChat.sendMessagePlaceholder')}
                  className="flex-1 bg-white border border-gray-300 rounded-xl px-4 py-3 text-gray-900 focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none shadow-sm"
                  disabled={isStreaming}
                />
                <button
                  onClick={handleSend}
                  disabled={!inputMessage.trim() || isStreaming}
                  className="px-6 py-3 bg-blue-600 hover:bg-blue-700 text-white rounded-xl font-medium transition-colors duration-200 disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2 cursor-pointer shadow-sm"
                >
                  <Send size={20} />
                </button>
              </div>
            </div>
          </>
        ) : (
          /* Empty State */
          <div className="flex-1 flex items-center justify-center bg-white">
            <div className="text-center">
              <div className="w-20 h-20 mx-auto mb-6 rounded-2xl bg-blue-50 flex items-center justify-center">
                <MessageCircle size={40} className="text-blue-600" />
              </div>
              <h2 className="text-2xl font-bold text-gray-900 mb-2">{t('singleChat.title')}</h2>
              <p className="text-gray-600 mb-6 max-w-md">
                {t('singleChat.description')}
              </p>
              <button
                onClick={() => setShowCreateModal(true)}
                className="inline-flex items-center gap-2 bg-blue-600 hover:bg-blue-700 text-white px-6 py-3 rounded-xl font-medium transition-colors duration-200 cursor-pointer shadow-sm"
              >
                <Plus size={20} />
                {t('singleChat.createSession')}
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
              <h2 className="text-2xl font-bold text-gray-900">{t('singleChat.createSessionTitle')}</h2>
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
                <label className="block text-sm font-medium text-gray-700 mb-2">{t('singleChat.sessionNameLabel')} *</label>
                <input
                  type="text"
                  value={newSessionName}
                  onChange={e => setNewSessionName(e.target.value)}
                  className="w-full bg-white border border-gray-300 rounded-xl px-4 py-3 text-gray-900 focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none"
                  placeholder={t('singleChat.sessionNamePlaceholder')}
                />
              </div>

              {/* Select Agent */}
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">
                  {t('singleChat.selectAgentLabel')} *
                </label>
                <select
                  value={selectedAgentId}
                  onChange={e => setSelectedAgentId(e.target.value)}
                  className="w-full bg-white border border-gray-300 rounded-xl px-4 py-3 text-gray-900 focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none"
                >
                  <option value="">{t('singleChat.selectAgentPlaceholder')}</option>
                  {agents.map(agent => (
                    <option key={agent.id} value={agent.id}>
                      {agent.name}
                    </option>
                  ))}
                </select>
              </div>
            </div>

            <div className="p-6 border-t border-gray-200 flex justify-end gap-3">
              <button
                onClick={() => setShowCreateModal(false)}
                className="px-6 py-2.5 text-gray-700 hover:text-gray-900 hover:bg-gray-100 rounded-xl transition-colors duration-200 cursor-pointer"
              >
                {t('common.cancel')}
              </button>
              <button
                onClick={handleCreate}
                disabled={isCreating || !newSessionName.trim() || !selectedAgentId}
                className="px-6 py-2.5 bg-blue-600 hover:bg-blue-700 text-white rounded-xl transition-colors duration-200 font-medium disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2 cursor-pointer shadow-sm"
              >
                {isCreating && <Loader2 size={16} className="animate-spin" />}
                {t('common.create')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
