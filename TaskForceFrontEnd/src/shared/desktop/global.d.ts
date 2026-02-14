import type { DesktopBridge } from './types';

declare global {
  interface Window {
    taskforceDesktop?: DesktopBridge;
  }
}

export {};
