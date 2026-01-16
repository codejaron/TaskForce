import React, { useState } from 'react';
import type { ToolCallDTO } from '../shared/api/types';

interface ToolCallCardProps {
  toolCall: ToolCallDTO;
  defaultExpanded?: boolean;
}

/**
 * 工具调用卡片组件
 * 显示工具名称、状态、耗时，可折叠展开查看完整参数和结果
 */
export const ToolCallCard: React.FC<ToolCallCardProps> = ({ toolCall, defaultExpanded = false }) => {
  const [expanded, setExpanded] = useState(defaultExpanded);

  // 状态图标
  const statusIcon = {
    RUNNING: (
      <svg className="w-3.5 h-3.5 animate-spin text-blue-500" fill="none" viewBox="0 0 24 24">
        <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
        <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
      </svg>
    ),
    SUCCESS: (
      <svg className="w-3.5 h-3.5 text-green-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
      </svg>
    ),
    FAILED: (
      <svg className="w-3.5 h-3.5 text-red-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
      </svg>
    )
  }[toolCall.status];

  // 状态颜色
  const statusColor = {
    RUNNING: 'border-blue-200 bg-blue-50',
    SUCCESS: 'border-green-200 bg-green-50',
    FAILED: 'border-red-200 bg-red-50'
  }[toolCall.status];

  // 格式化 JSON
  const formatJson = (jsonStr: string): string => {
    try {
      return JSON.stringify(JSON.parse(jsonStr), null, 2);
    } catch {
      return jsonStr;
    }
  };

  // 截断结果
  const truncateResult = (result: string, maxLen: number): string => {
    if (result.length <= maxLen) return result;
    return result.substring(0, maxLen) + '\n... (truncated)';
  };

  return (
    <div className={`border rounded-lg overflow-hidden text-xs ${statusColor}`}>
      {/* 头部：工具名称 + 状态 + 耗时 */}
      <div
        className="flex items-center justify-between px-2.5 py-1.5 cursor-pointer hover:bg-opacity-80"
        onClick={() => setExpanded(!expanded)}
      >
        <div className="flex items-center gap-1.5">
          {/* 工具图标 */}
          <svg className="w-3.5 h-3.5 text-gray-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
          </svg>
          <span className="font-mono font-medium text-gray-800">{toolCall.toolName}</span>
          {toolCall.serverName && (
            <span className="text-gray-400">({toolCall.serverName})</span>
          )}
          {statusIcon}
        </div>
        <div className="flex items-center gap-2 text-gray-500">
          {toolCall.durationMs !== undefined && (
            <span>{toolCall.durationMs}ms</span>
          )}
          {/* 展开/折叠图标 */}
          <svg
            className={`w-3.5 h-3.5 transition-transform ${expanded ? 'rotate-180' : ''}`}
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
          >
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
          </svg>
        </div>
      </div>

      {/* 展开内容：参数 + 结果 */}
      {expanded && (
        <div className="border-t border-gray-200 px-2.5 py-2 space-y-2 bg-white bg-opacity-50">
          {/* 参数 */}
          {toolCall.toolArgs && (
            <div>
              <div className="font-medium text-gray-600 mb-1">参数</div>
              <pre className="bg-white rounded p-2 overflow-x-auto max-h-32 text-gray-700 whitespace-pre-wrap break-all">
                {formatJson(toolCall.toolArgs)}
              </pre>
            </div>
          )}

          {/* 结果 */}
          {toolCall.toolResult && (
            <div>
              <div className="font-medium text-gray-600 mb-1">结果</div>
              <pre className="bg-white rounded p-2 overflow-x-auto max-h-48 text-gray-700 whitespace-pre-wrap break-all">
                {truncateResult(toolCall.toolResult, 2000)}
              </pre>
            </div>
          )}

          {/* 错误信息 */}
          {toolCall.errorMessage && (
            <div className="text-red-600 bg-red-50 rounded p-2">
              {toolCall.errorMessage}
            </div>
          )}
        </div>
      )}
    </div>
  );
};

interface ToolCallListProps {
  toolCalls: ToolCallDTO[];
}

/**
 * 工具调用列表组件
 * 展示一个步骤中的所有工具调用
 */
export const ToolCallList: React.FC<ToolCallListProps> = ({ toolCalls }) => {
  if (!toolCalls || toolCalls.length === 0) {
    return null;
  }

  return (
    <div className="mt-2 space-y-1.5">
      {toolCalls.map((tc, idx) => (
        <ToolCallCard
          key={tc.toolCallId}
          toolCall={tc}
          defaultExpanded={idx === 0 && toolCalls.length === 1}  // 只有一个时默认展开
        />
      ))}
    </div>
  );
};
