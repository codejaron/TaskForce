import { create } from 'zustand';
import { api } from '../../../shared/api';
import { sseConnectionManager, type SSEEventHandlers } from '../../../shared/sse';
import type { WorkerInstance } from '../../../shared/api/types';

/**
 * Worker message type
 */
export interface WorkerMessage {
  id: string;
  content: string;
  role: 'user' | 'assistant' | 'system';
  timestamp: string;
  type?: 'text' | 'tool_use' | 'tool_result';
}

/**
 * Worker state
 */
export interface WorkerState {
  instanceId: string;
  name: string;
  status: 'idle' | 'working' | 'shutdown';
  currentTaskId: string | null;
  messages: WorkerMessage[];
  isConnected: boolean;
  sseConnectionId?: string;
}

/**
 * Worker store state
 */
interface WorkerStoreState {
  workers: Map<string, WorkerState>;
  selectedWorkerId: string | null;
  isLoading: boolean;
  error: string | null;
  currentSessionId: string | null;

  // Actions
  fetchWorkers: (sessionId: string) => Promise<void>;
  sendToWorker: (sessionId: string, instanceId: string, message: string) => Promise<void>;
  subscribeWorkerEvents: (sessionId: string, instanceId: string) => void;
  unsubscribeWorker: (instanceId: string) => void;
  handleWorkerEvent: (instanceId: string, event: any) => void;
  selectWorker: (instanceId: string | null) => void;
  clearWorkers: () => void;
  updateWorkerStatus: (instanceId: string, status: 'idle' | 'working' | 'shutdown') => void;
}

/**
 * Map backend WorkerStatus to frontend status
 */
function mapWorkerStatus(backendStatus: string): 'idle' | 'working' | 'shutdown' {
  switch (backendStatus) {
    case 'IDLE':
      return 'idle';
    case 'BUSY':
      return 'working';
    case 'STOPPED':
    case 'ERROR':
      return 'shutdown';
    default:
      return 'idle';
  }
}

export const useWorkerStore = create<WorkerStoreState>((set, get) => ({
  workers: new Map(),
  selectedWorkerId: null,
  isLoading: false,
  error: null,
  currentSessionId: null,

  fetchWorkers: async (sessionId: string) => {
    set({ isLoading: true, error: null, currentSessionId: sessionId });
    try {
      const workerInstances = await api.team.getWorkers(sessionId);
      console.log('[Worker Store] Fetched workers:', workerInstances);

      const workersMap = new Map<string, WorkerState>();

      workerInstances.forEach((instance: WorkerInstance) => {
        const existingWorker = get().workers.get(instance.instanceId);

        workersMap.set(instance.instanceId, {
          instanceId: instance.instanceId,
          name: instance.agentName,
          status: mapWorkerStatus(instance.status),
          currentTaskId: instance.currentTask || null,
          messages: existingWorker?.messages || [],
          isConnected: existingWorker?.isConnected || false,
          sseConnectionId: existingWorker?.sseConnectionId
        });

        // Auto-subscribe to new workers
        if (!existingWorker) {
          setTimeout(() => {
            get().subscribeWorkerEvents(sessionId, instance.instanceId);
          }, 100);
        }
      });

      set({ workers: workersMap, isLoading: false });
    } catch (error: any) {
      console.error('[Worker Store] Failed to fetch workers:', error);
      set({
        error: error.message || 'Failed to load workers',
        isLoading: false
      });
    }
  },

  sendToWorker: async (sessionId: string, instanceId: string, message: string) => {
    try {
      await api.team.sendMessageToWorker(sessionId, instanceId, message);
      console.log(`[Worker Store] Message sent to worker ${instanceId}`);

      // Add user message to local state
      const workers = new Map(get().workers);
      const worker = workers.get(instanceId);
      if (worker) {
        worker.messages.push({
          id: `msg-${Date.now()}`,
          content: message,
          role: 'user',
          timestamp: new Date().toISOString(),
          type: 'text'
        });
        workers.set(instanceId, worker);
        set({ workers });
      }
    } catch (error: any) {
      console.error(`[Worker Store] Failed to send message to worker ${instanceId}:`, error);
      set({ error: error.message || 'Failed to send message' });
    }
  },

  subscribeWorkerEvents: (sessionId: string, instanceId: string) => {
    const worker = get().workers.get(instanceId);
    if (worker?.sseConnectionId) {
      console.log(`[Worker Store] Worker ${instanceId} already subscribed`);
      return;
    }

    const url = `/api/v2/team/session/${sessionId}/worker/${instanceId}/events`;

    const eventHandlers: SSEEventHandlers = {
      onOpen: () => {
        console.log(`[Worker Store] SSE connection opened for worker ${instanceId}`);
        const workers = new Map(get().workers);
        const worker = workers.get(instanceId);
        if (worker) {
          worker.isConnected = true;
          workers.set(instanceId, worker);
          set({ workers });
        }
      },

      onMessage: (event) => {
        console.log(`[Worker Store] SSE message from worker ${instanceId}:`, event);
        get().handleWorkerEvent(instanceId, event);
      },

      onError: (error) => {
        console.error(`[Worker Store] SSE error for worker ${instanceId}:`, error);
        const workers = new Map(get().workers);
        const worker = workers.get(instanceId);
        if (worker) {
          worker.isConnected = false;
          workers.set(instanceId, worker);
          set({ workers });
        }
      },

      onClose: () => {
        console.log(`[Worker Store] SSE connection closed for worker ${instanceId}`);
        const workers = new Map(get().workers);
        const worker = workers.get(instanceId);
        if (worker) {
          worker.isConnected = false;
          workers.set(instanceId, worker);
          set({ workers });
        }
      }
    };

    const connectionId = sseConnectionManager.createConnection({
      url,
      eventHandlers
    });

    console.log(`[Worker Store] Created SSE connection ${connectionId} for worker ${instanceId}`);

    const workers = new Map(get().workers);
    const workerState = workers.get(instanceId);
    if (workerState) {
      workerState.sseConnectionId = connectionId;
      workers.set(instanceId, workerState);
      set({ workers });
    }
  },

  unsubscribeWorker: (instanceId: string) => {
    const worker = get().workers.get(instanceId);
    if (worker?.sseConnectionId) {
      console.log(`[Worker Store] Unsubscribing worker ${instanceId}`);
      sseConnectionManager.closeConnection(worker.sseConnectionId);

      const workers = new Map(get().workers);
      const updatedWorker = workers.get(instanceId);
      if (updatedWorker) {
        updatedWorker.sseConnectionId = undefined;
        updatedWorker.isConnected = false;
        workers.set(instanceId, updatedWorker);
        set({ workers });
      }
    }
  },

  handleWorkerEvent: (instanceId: string, event: any) => {
    try {
      const data = typeof event.data === 'string' ? JSON.parse(event.data) : event.data;
      const workers = new Map(get().workers);
      const worker = workers.get(instanceId);

      if (!worker) {
        console.warn(`[Worker Store] Worker ${instanceId} not found`);
        return;
      }

      // Handle different event types
      switch (event.event || data.type) {
        case 'message':
        case 'worker_message':
          worker.messages.push({
            id: data.id || `msg-${Date.now()}`,
            content: data.content || data.message,
            role: 'assistant',
            timestamp: data.timestamp || new Date().toISOString(),
            type: data.messageType || 'text'
          });
          break;

        case 'status_change':
        case 'worker_status':
          worker.status = mapWorkerStatus(data.status);
          break;

        case 'task_assigned':
          worker.currentTaskId = data.taskId;
          worker.status = 'working';
          break;

        case 'task_completed':
          worker.currentTaskId = null;
          worker.status = 'idle';
          break;

        case 'worker_shutdown':
          worker.status = 'shutdown';
          worker.isConnected = false;
          // Auto-unsubscribe on shutdown
          if (worker.sseConnectionId) {
            sseConnectionManager.closeConnection(worker.sseConnectionId);
            worker.sseConnectionId = undefined;
          }
          break;

        default:
          console.log(`[Worker Store] Unknown event type for worker ${instanceId}:`, event.event || data.type);
      }

      workers.set(instanceId, worker);
      set({ workers });
    } catch (error) {
      console.error(`[Worker Store] Failed to handle worker event for ${instanceId}:`, error);
    }
  },

  selectWorker: (instanceId: string | null) => {
    set({ selectedWorkerId: instanceId });
  },

  clearWorkers: () => {
    // Unsubscribe all workers
    const workers = get().workers;
    workers.forEach((worker) => {
      if (worker.sseConnectionId) {
        sseConnectionManager.closeConnection(worker.sseConnectionId);
      }
    });

    set({
      workers: new Map(),
      selectedWorkerId: null,
      currentSessionId: null,
      error: null
    });
  },

  updateWorkerStatus: (instanceId: string, status: 'idle' | 'working' | 'shutdown') => {
    const workers = new Map(get().workers);
    const worker = workers.get(instanceId);
    if (worker) {
      worker.status = status;
      workers.set(instanceId, worker);
      set({ workers });
    }
  }
}));
