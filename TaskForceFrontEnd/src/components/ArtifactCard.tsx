import React from 'react';
import ReactMarkdown from 'react-markdown';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneLight } from 'react-syntax-highlighter/dist/esm/styles/prism';
import remarkGfm from 'remark-gfm';

interface ArtifactCardProps {
  artifactKey: string;
  content: string;
}

/**
 * Artifact 展示卡片
 * 用于渲染消息中的 artifact 内容
 */
export const ArtifactCard: React.FC<ArtifactCardProps> = ({ artifactKey, content }) => {
  return (
    <div className="artifact-card border border-purple-200 rounded-lg p-3 my-3 bg-purple-50">
      {/* 头部标签 */}
      <div className="flex items-center gap-2 mb-2 pb-2 border-b border-purple-200">
        <span className="text-purple-600">📦</span>
        <span className="text-sm font-medium text-purple-700">产物</span>
        <code className="text-xs bg-purple-100 px-2 py-0.5 rounded text-purple-800">
          {artifactKey}
        </code>
      </div>

      {/* Artifact 内容（通常是 Markdown/代码） */}
      <div className="artifact-content">
        <ReactMarkdown
          remarkPlugins={[remarkGfm]}
          components={{
            p: ({ children }) => <p className="mb-2 last:mb-0 break-words">{children}</p>,
            code(props: any) {
              const { inline, className, children, ...rest } = props;
              const match = /language-(\w+)/.exec(className || '');
              const text = String(children);

              // 有语言标记的代码块
              if (!inline && match) {
                return (
                  <div className="my-2 overflow-x-auto rounded">
                    <SyntaxHighlighter
                      {...rest}
                      style={oneLight}
                      language={match[1]}
                      PreTag="div"
                      customStyle={{ margin: 0, fontSize: '0.75rem' }}
                    >
                      {text.replace(/\n$/, '')}
                    </SyntaxHighlighter>
                  </div>
                );
              }

              // 无语言标记的代码块
              if (!inline) {
                // 单行短文本按行内代码处理
                if (!text.includes('\n') && text.length < 100) {
                  return <code className="bg-purple-100 px-1.5 py-0.5 rounded text-xs font-mono" {...rest}>{children}</code>;
                }
                // 多行代码块
                return (
                  <div className="my-2 overflow-x-auto rounded">
                    <SyntaxHighlighter
                      {...rest}
                      style={oneLight}
                      language="text"
                      PreTag="div"
                      customStyle={{ margin: 0, fontSize: '0.75rem' }}
                    >
                      {text.replace(/\n$/, '')}
                    </SyntaxHighlighter>
                  </div>
                );
              }

              // 行内代码
              return <code className="bg-purple-100 px-1.5 py-0.5 rounded text-xs font-mono" {...rest}>{children}</code>;
            },
            ul: ({ children }) => <ul className="list-disc list-outside mb-2 space-y-1 ml-4">{children}</ul>,
            ol: ({ children }) => <ol className="list-decimal list-outside mb-2 space-y-1 ml-4">{children}</ol>,
            li: ({ children }) => <li className="break-words">{children}</li>,
            h1: ({ children }) => <h1 className="text-base font-bold mb-2 break-words">{children}</h1>,
            h2: ({ children }) => <h2 className="text-sm font-bold mb-2 break-words">{children}</h2>,
            h3: ({ children }) => <h3 className="text-xs font-bold mb-1 break-words">{children}</h3>,
            blockquote: ({ children }) => <blockquote className="border-l-2 border-purple-400 pl-3 my-2 italic break-words">{children}</blockquote>,
            a: ({ href, children }) => <a href={href} className="text-purple-600 hover:underline break-all" target="_blank" rel="noopener noreferrer">{children}</a>,
          }}
        >
          {content}
        </ReactMarkdown>
      </div>
    </div>
  );
};
