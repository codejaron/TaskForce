import type { DesktopBridge, DesktopProjectSelection } from './types';

function getDesktopBridge(): DesktopBridge | null {
  if (typeof window === 'undefined') {
    return null;
  }
  return window.taskforceDesktop ?? null;
}

export const desktopClient = {
  isDesktop(): boolean {
    return Boolean(getDesktopBridge()?.isDesktop);
  },

  async pickProjectDirectory(sessionId?: string): Promise<DesktopProjectSelection> {
    const bridge = getDesktopBridge();
    if (!bridge) {
      return { canceled: true };
    }
    return bridge.pickProjectDirectory(sessionId);
  },

  async getSelectedProjectDirectory(sessionId?: string): Promise<string | null> {
    const bridge = getDesktopBridge();
    if (!bridge) {
      return null;
    }
    return bridge.getSelectedProjectDirectory(sessionId);
  }
};
