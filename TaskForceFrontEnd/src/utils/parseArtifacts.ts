/**
 * 解析后的内容片段
 */
export interface ParsedContent {
  type: 'text' | 'artifact';
  key?: string;          // artifact 的 key
  content: string;       // 内容（text 或 artifact 内部内容）
}

/**
 * 解析消息中的 artifact 标签
 * 将消息分割为普通文本和 artifact 片段
 *
 * @param text 原始消息文本
 * @returns 解析后的内容片段数组
 */
export function parseArtifacts(text: string): ParsedContent[] {
  if (!text) return [{ type: 'text', content: '' }];

  // 匹配 <artifact key="...">...</artifact> 或 </artifact >（允许结束标签有空格）
  const regex = /<artifact\s+key="([a-zA-Z0-9_-]+)"\s*>([\s\S]*?)<\/artifact\s*>/g;

  const parts: ParsedContent[] = [];
  let lastIndex = 0;
  let match;

  while ((match = regex.exec(text)) !== null) {
    // 1. 标签前的普通文本
    if (match.index > lastIndex) {
      parts.push({
        type: 'text',
        content: text.slice(lastIndex, match.index)
      });
    }

    // 2. Artifact 内容
    parts.push({
      type: 'artifact',
      key: match[1],
      content: match[2]
    });

    lastIndex = regex.lastIndex;
  }

  // 3. 剩余的文本
  if (lastIndex < text.length) {
    parts.push({
      type: 'text',
      content: text.slice(lastIndex)
    });
  }

  return parts;
}

/**
 * 简单剔除 artifact 标签（用于流式传输预览）
 * 只移除标签本身，保留内部内容
 *
 * @param text 原始文本
 * @returns 移除标签后的文本
 */
export function stripArtifactTags(text: string): string {
  if (!text) return text;
  // 移除开始和结束标签，保留内容（允许结束标签有空格）
  return text.replace(/<artifact\s+key="[a-zA-Z0-9_-]+"\s*>/g, '')
             .replace(/<\/artifact\s*>/g, '');
}
