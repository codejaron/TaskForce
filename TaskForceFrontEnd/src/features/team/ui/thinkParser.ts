export type ThinkParseResult = {
  visibleContent: string;
  thoughts: string[];
  hasUnclosedThink: boolean;
};

const THINK_TAG_PATTERN = /<\/?\s*think\s*>/gi;

export function parseThinkContent(rawContent: string): ThinkParseResult {
  if (!rawContent) {
    return {
      visibleContent: '',
      thoughts: [],
      hasUnclosedThink: false
    };
  }

  let lastIndex = 0;
  let inThinkBlock = false;
  const visibleParts: string[] = [];
  const thinkingParts: string[] = [];
  const thoughts: string[] = [];

  rawContent.replace(THINK_TAG_PATTERN, (tag, offset) => {
    const index = Number(offset);
    const chunk = rawContent.slice(lastIndex, index);
    if (inThinkBlock) {
      thinkingParts.push(chunk);
    } else {
      visibleParts.push(chunk);
    }

    const normalizedTag = tag.toLowerCase();
    if (normalizedTag.startsWith('</')) {
      if (inThinkBlock) {
        const text = normalizeBlockText(thinkingParts.join(''));
        if (text) {
          thoughts.push(text);
        }
        thinkingParts.length = 0;
        inThinkBlock = false;
      } else {
        visibleParts.push(tag);
      }
    } else if (inThinkBlock) {
      // 嵌套 think 作为普通文本保留在当前思考块中。
      thinkingParts.push(tag);
    } else {
      inThinkBlock = true;
      thinkingParts.length = 0;
    }

    lastIndex = index + tag.length;
    return tag;
  });

  const tail = rawContent.slice(lastIndex);
  if (inThinkBlock) {
    thinkingParts.push(tail);
    const text = normalizeBlockText(thinkingParts.join(''));
    if (text) {
      thoughts.push(text);
    }
  } else {
    visibleParts.push(tail);
  }

  return {
    visibleContent: normalizeBlockText(visibleParts.join('')),
    thoughts,
    hasUnclosedThink: inThinkBlock
  };
}

function normalizeBlockText(text: string): string {
  return text
    .replace(/\r\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}
