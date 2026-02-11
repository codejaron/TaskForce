import { fetchEventSource } from '@microsoft/fetch-event-source';

/**
 * SSE Message event structure
 */
export interface SSEMessageEvent {
  id?: string;
  event?: string;
  data: string;
  retry?: number;
}

/**
 * Event handlers for SSE connection
 */
export interface SSEEventHandlers {
  onOpen?: (response: Response) => void | Promise<void>;
  onMessage?: (event: SSEMessageEvent) => void;
  onError?: (error: Error) => void;
  onClose?: () => void;
}

/**
 * Configuration for SSE connection
 */
export interface SSEConnectionConfig {
  url: string;
  eventHandlers: SSEEventHandlers;
  reconnect?: boolean;
  maxRetries?: number;
  initialDelay?: number;
  maxDelay?: number;
  backoffMultiplier?: number;
  heartbeatInterval?: number;
  heartbeatTimeout?: number;
}

/**
 * Internal connection state
 */
interface SSEConnection {
  id: string;
  url: string;
  controller: AbortController;
  reconnectAttempts: number;
  reconnectTimeoutId: ReturnType<typeof setTimeout> | null;
  heartbeatIntervalId: ReturnType<typeof setInterval> | null;
  lastHeartbeat: number;
  config: SSEConnectionConfig;
}

/**
 * Default reconnection configuration
 */
const DEFAULT_RECONNECT_CONFIG = {
  reconnect: true,
  maxRetries: 5,
  initialDelay: 1000,
  maxDelay: 32000,
  backoffMultiplier: 2,
  heartbeatInterval: 30000,
  heartbeatTimeout: 60000
};

/**
 * SSE Connection Manager
 * Manages multiple SSE connections with auto-reconnect and heartbeat detection
 */
export class SSEConnectionManager {
  private connections: Map<string, SSEConnection>;
  private connectionCounter: number;

  constructor() {
    this.connections = new Map();
    this.connectionCounter = 0;
  }

  /**
   * Create a new SSE connection
   * @param config Connection configuration
   * @returns Connection ID
   */
  createConnection(config: SSEConnectionConfig): string {
    const connectionId = `sse-${++this.connectionCounter}-${Date.now()}`;

    const fullConfig: SSEConnectionConfig = {
      ...DEFAULT_RECONNECT_CONFIG,
      ...config
    };

    const connection: SSEConnection = {
      id: connectionId,
      url: config.url,
      controller: new AbortController(),
      reconnectAttempts: 0,
      reconnectTimeoutId: null,
      heartbeatIntervalId: null,
      lastHeartbeat: Date.now(),
      config: fullConfig
    };

    this.connections.set(connectionId, connection);
    this.connect(connectionId);

    if (fullConfig.heartbeatInterval && fullConfig.heartbeatInterval > 0) {
      this.setupHeartbeat(connectionId);
    }

    return connectionId;
  }

  /**
   * Close a specific connection
   * @param connectionId Connection ID to close
   */
  closeConnection(connectionId: string): void {
    const connection = this.connections.get(connectionId);
    if (!connection) {
      console.warn(`[SSEConnectionManager] Connection ${connectionId} not found`);
      return;
    }

    this.cleanupConnection(connection);
    this.connections.delete(connectionId);
    console.log(`[SSEConnectionManager] Connection ${connectionId} closed`);
  }

  /**
   * Close all connections
   */
  closeAll(): void {
    console.log(`[SSEConnectionManager] Closing all ${this.connections.size} connections`);

    for (const [connectionId] of this.connections) {
      this.closeConnection(connectionId);
    }
  }

  /**
   * Get connection status
   * @param connectionId Connection ID
   * @returns Connection status or null if not found
   */
  getConnectionStatus(connectionId: string): {
    id: string;
    url: string;
    reconnectAttempts: number;
    lastHeartbeat: number;
  } | null {
    const connection = this.connections.get(connectionId);
    if (!connection) {
      return null;
    }

    return {
      id: connection.id,
      url: connection.url,
      reconnectAttempts: connection.reconnectAttempts,
      lastHeartbeat: connection.lastHeartbeat
    };
  }

  /**
   * Get all active connection IDs
   */
  getActiveConnections(): string[] {
    return Array.from(this.connections.keys());
  }

  /**
   * Internal: Establish SSE connection
   */
  private connect(connectionId: string): void {
    const connection = this.connections.get(connectionId);
    if (!connection) {
      return;
    }

    const { url, config, controller } = connection;
    const { eventHandlers, reconnect, maxRetries } = config;
    const self = this;

    fetchEventSource(url, {
      signal: controller.signal,

      onopen: async (response) => {
        if (response.ok) {
          console.log(`[SSEConnectionManager] Connection ${connectionId} established`);
          connection.lastHeartbeat = Date.now();

          if (connection.reconnectAttempts > 0) {
            console.log(`[SSEConnectionManager] Connection ${connectionId} reconnected successfully`);
            connection.reconnectAttempts = 0;
          }

          if (eventHandlers.onOpen) {
            await eventHandlers.onOpen(response);
          }
        } else {
          throw new Error(`SSE connection failed with status ${response.status}`);
        }
      },

      onmessage: (ev) => {
        connection.lastHeartbeat = Date.now();

        if (eventHandlers.onMessage) {
          eventHandlers.onMessage(ev);
        }
      },

      onerror: (err) => {
        if (err.name === 'AbortError') {
          return;
        }

        console.error(`[SSEConnectionManager] Connection ${connectionId} error:`, err);

        if (eventHandlers.onError) {
          eventHandlers.onError(err instanceof Error ? err : new Error(String(err)));
        }

        if (reconnect && connection.reconnectAttempts < (maxRetries || DEFAULT_RECONNECT_CONFIG.maxRetries)) {
          self.scheduleReconnect(connectionId);
        } else if (connection.reconnectAttempts >= (maxRetries || DEFAULT_RECONNECT_CONFIG.maxRetries)) {
          console.error(`[SSEConnectionManager] Connection ${connectionId} max reconnection attempts reached`);
          self.closeConnection(connectionId);
        }
      },

      onclose: () => {
        console.log(`[SSEConnectionManager] Connection ${connectionId} closed`);

        if (eventHandlers.onClose) {
          eventHandlers.onClose();
        }

        if (controller.signal.aborted) {
          console.log(`[SSEConnectionManager] Connection ${connectionId} aborted intentionally`);
          return;
        }

        if (reconnect && connection.reconnectAttempts < (maxRetries || DEFAULT_RECONNECT_CONFIG.maxRetries)) {
          self.scheduleReconnect(connectionId);
        }
      }
    }).catch(err => {
      if (err.name !== 'AbortError') {
        console.error(`[SSEConnectionManager] Connection ${connectionId} fatal error:`, err);

        if (eventHandlers.onError) {
          eventHandlers.onError(err instanceof Error ? err : new Error(String(err)));
        }
      }
    });
  }

  /**
   * Internal: Schedule reconnection with exponential backoff
   */
  private scheduleReconnect(connectionId: string): void {
    const connection = this.connections.get(connectionId);
    if (!connection) {
      return;
    }

    const delay = this.getReconnectDelay(connection.reconnectAttempts, connection.config);
    connection.reconnectAttempts++;

    console.log(
      `[SSEConnectionManager] Reconnecting ${connectionId} in ${Math.round(delay)}ms ` +
      `(attempt ${connection.reconnectAttempts}/${connection.config.maxRetries})`
    );

    connection.reconnectTimeoutId = setTimeout(() => {
      connection.controller = new AbortController();
      this.connect(connectionId);
    }, delay);
  }

  /**
   * Internal: Calculate reconnect delay with exponential backoff and jitter
   */
  private getReconnectDelay(retryCount: number, config: SSEConnectionConfig): number {
    const initialDelay = config.initialDelay || DEFAULT_RECONNECT_CONFIG.initialDelay;
    const maxDelay = config.maxDelay || DEFAULT_RECONNECT_CONFIG.maxDelay;
    const backoffMultiplier = config.backoffMultiplier || DEFAULT_RECONNECT_CONFIG.backoffMultiplier;

    const baseDelay = initialDelay * Math.pow(backoffMultiplier, retryCount);
    const jitter = Math.random() * 1000;

    return Math.min(baseDelay + jitter, maxDelay);
  }

  /**
   * Internal: Set up heartbeat monitoring
   */
  private setupHeartbeat(connectionId: string): void {
    const connection = this.connections.get(connectionId);
    if (!connection) {
      return;
    }

    const heartbeatInterval = connection.config.heartbeatInterval || DEFAULT_RECONNECT_CONFIG.heartbeatInterval;
    const heartbeatTimeout = connection.config.heartbeatTimeout || DEFAULT_RECONNECT_CONFIG.heartbeatTimeout;

    connection.heartbeatIntervalId = setInterval(() => {
      const timeSinceLastHeartbeat = Date.now() - connection.lastHeartbeat;

      if (timeSinceLastHeartbeat > heartbeatTimeout) {
        console.warn(
          `[SSEConnectionManager] Connection ${connectionId} heartbeat timeout ` +
          `(${Math.round(timeSinceLastHeartbeat / 1000)}s since last message)`
        );

        connection.controller.abort();

        if (connection.config.reconnect) {
          this.scheduleReconnect(connectionId);
        }
      }
    }, heartbeatInterval);
  }

  /**
   * Internal: Clean up connection resources
   */
  private cleanupConnection(connection: SSEConnection): void {
    if (connection.reconnectTimeoutId) {
      clearTimeout(connection.reconnectTimeoutId);
      connection.reconnectTimeoutId = null;
    }

    if (connection.heartbeatIntervalId) {
      clearInterval(connection.heartbeatIntervalId);
      connection.heartbeatIntervalId = null;
    }

    try {
      connection.controller.abort();
    } catch (e) {
      console.debug(`[SSEConnectionManager] Abort completed:`, e instanceof Error ? e.message : e);
    }
  }
}

// Export singleton instance
export const sseConnectionManager = new SSEConnectionManager();
