import React, { useState, useRef, useEffect } from 'react';
import { Send, Loader2 } from 'lucide-react';
import { clsx } from 'clsx';
import { useTeamStore } from '../model/store';
import type { WorkerMessage } from '../model/store';

export const WorkerChatPanel: React.FC = () => {
  const { activeWorkerId, workerMessages, members, sendToWorker } = useTeamStore();
  const [input, setInput] = useState('');
  const [isSending, setIsSending] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);

  const messages = activeWorkerId ? workerMessages[activeWorkerId] || [] : [];
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
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  if (!activeWorkerId) {
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
        <div className="flex items-end gap-2">
          <textarea
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="发送消息给 Worker..."
            className="flex-1 resize-none bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-green-500 focus:border-transparent outline-none"
            rows={2}
            disabled={isSending}
          />
          <button
            onClick={handleSend}
            disabled={!input.trim() || isSending}
            className="px-4 py-2 bg-green-600 hover:bg-green-700 text-white rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2"
          >
            {isSending ? <Loader2 size={16} className="animate-spin" /> : <Send size={16} />}
          </button>
        </div>
      </div>
    </div>
  );
};

const MessageBubble: React.FC<{ message: WorkerMessage }> = ({ message }) => {
  const isUser = message.type === 'user';

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
        <pre className="whitespace-pre-wrap break-words font-sans">
          {message.content}
        </pre>

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
