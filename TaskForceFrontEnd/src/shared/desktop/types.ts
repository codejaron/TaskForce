export interface DesktopProjectSelection {
  canceled: boolean;
  path?: string;
}

export interface DesktopBridge {
  isDesktop: boolean;
  isAvailable: () => Promise<boolean>;
  pickProjectDirectory: (sessionId?: string) => Promise<DesktopProjectSelection>;
  getSelectedProjectDirectory: (sessionId?: string) => Promise<string | null>;
}
