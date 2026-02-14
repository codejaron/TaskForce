function normalizeBase(base: string): string {
  if (!base) return '/api';
  return base.endsWith('/') ? base.slice(0, -1) : base;
}

function detectApiBase(): string {
  if (typeof window === 'undefined') {
    return '/api';
  }

  // Electron fallback when renderer is loaded from file://dist/index.html.
  // In that case relative '/api' points to file:// and all requests fail silently.
  if (window.location.protocol === 'file:') {
    return 'http://localhost:8080/api';
  }

  return '/api';
}

export const API_BASE = normalizeBase(detectApiBase());

export function apiUrl(path: string): string {
  const normalizedPath = path.startsWith('/') ? path : `/${path}`;
  return `${API_BASE}${normalizedPath}`;
}
