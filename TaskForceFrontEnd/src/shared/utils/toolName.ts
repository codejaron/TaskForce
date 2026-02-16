export function getToolDisplayName(rawToolName?: string | null, fallback = 'tool'): string {
  const normalized = (rawToolName ?? '').trim();
  if (!normalized || normalized.toLowerCase() === 'unknown') {
    return fallback;
  }

  const segments = normalized
    .split('::')
    .map(segment => segment.trim())
    .filter(Boolean);

  if (segments.length === 0) {
    return fallback;
  }

  const toolName = segments[segments.length - 1];
  if (!toolName || toolName.toLowerCase() === 'unknown') {
    return fallback;
  }
  return toolName;
}
