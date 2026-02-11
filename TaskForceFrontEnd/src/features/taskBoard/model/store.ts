import { create } from 'zustand';
import { api } from '../../../shared/api';
import type { Task, TaskStatus } from '../../../shared/api';
import { fetchEventSource } from '@microsoft/fetch-event-source';

interface TaskBoardState {
  tasks: Task[];
  selectedTaskId: string | null;
  isLoading: boolean;
  error: string | null;

  fetchTasks: (sessionId: string) => Promise<void>;
  subscribeTaskEvents: (sessionId: string) => void;
  getTaskDetail: (taskId: string) => Task | null;
  handleTaskEvent: (event: TaskEvent) => void;
  selectTask: (taskId: string | null) => void;
  disconnectStream: () => void;
}

// Task Event Types
interface BaseTaskEvent {
  eventId: string;
  sessionId: string;
  timestamp: string;
}

interface TaskCreatedEvent extends BaseTaskEvent {
  type: 'TaskCreated';
  task: Task;
}

interface TaskClaimedEvent extends BaseTaskEvent {
  type: 'TaskClaimed';
  taskId: string;
  assignedTo: string;
}

interface TaskCompletedEvent extends BaseTaskEvent {
  type: 'TaskCompleted';
  taskId: string;
}

interface TaskUnblockedEvent extends BaseTaskEvent {
  type: 'TaskUnblocked';
  taskId: string;
}

interface TaskFailedEvent extends BaseTaskEvent {
  type: 'TaskFailed';
  taskId: string;
  error?: string;
}

type TaskEvent =
  | TaskCreatedEvent
  | TaskClaimedEvent
  | TaskCompletedEvent
  | TaskUnblockedEvent
  | TaskFailedEvent;

// Store current AbortController for SSE connection
let currentAbortController: AbortController | null = null;

// Event deduplication
const processedEventIds = new Map<string, Set<string>>();

function getEventIdSet(sessionId: string): Set<string> {
  if (!processedEventIds.has(sessionId)) {
    processedEventIds.set(sessionId, new Set<string>());
  }
  return processedEventIds.get(sessionId)!;
}

function clearEventIdSet(sessionId: string) {
  processedEventIds.delete(sessionId);
}

export const useTaskBoardStore = create<TaskBoardState>((set, get) => ({
  tasks: [],
  selectedTaskId: null,
  isLoading: false,
  error: null,

  fetchTasks: async (sessionId: string) => {
    set({ isLoading: true, error: null });
    try {
      const taskBoard = await api.team.getTaskBoard(sessionId);
      set({ tasks: taskBoard.tasks || [], isLoading: false });
    } catch (error: unknown) {
      console.error('[TaskBoard] Failed to fetch tasks:', error);
      set({
        tasks: [],
        isLoading: false,
        error: error instanceof Error ? error.message : 'Failed to load tasks'
      });
    }
  },

  subscribeTaskEvents: (sessionId: string) => {
    const { disconnectStream } = get();
    disconnectStream();

    // Clear previous event IDs for this session
    clearEventIdSet(sessionId);

    const eventSourceUrl = `/api/v2/team/session/${sessionId}/taskboard/events`;
    const controller = new AbortController();
    currentAbortController = controller;

    console.log('[TaskBoard] Subscribing to task events:', eventSourceUrl);

    fetchEventSource(eventSourceUrl, {
      signal: controller.signal,

      async onopen(response) {
        if (response.ok) {
          console.log('[TaskBoard SSE] Connection established');
        } else {
          throw new Error(`SSE connection failed with status ${response.status}`);
        }
      },

      onmessage(ev) {
        try {
          const eventData = JSON.parse(ev.data);

          // Deduplicate events
          const eventId = eventData.eventId;
          if (eventId) {
            const eventIdSet = getEventIdSet(sessionId);
            if (eventIdSet.has(eventId)) {
              console.debug('[TaskBoard] Duplicate event ignored:', eventId);
              return;
            }
            eventIdSet.add(eventId);
          }

          // Handle the event
          get().handleTaskEvent(eventData);
        } catch (error) {
          console.error('[TaskBoard] Failed to parse event:', error);
        }
      },

      onerror(err) {
        if (err.name === 'AbortError') {
          return;
        }
        console.error('[TaskBoard SSE] Connection error:', err);
        set({ error: 'Connection lost. Please refresh.' });
      },

      onclose() {
        console.log('[TaskBoard SSE] Connection closed');
      }
    }).catch(err => {
      if (err.name !== 'AbortError') {
        console.error('[TaskBoard SSE] Fatal error:', err);
        set({ error: err.message });
      }
    });
  },

  getTaskDetail: (taskId: string) => {
    const { tasks } = get();
    return tasks.find(task => task.id === taskId) || null;
  },

  handleTaskEvent: (event: TaskEvent) => {
    const { tasks } = get();
    console.log('[TaskBoard] Handling event:', event.type, event);

    switch (event.type) {
      case 'TaskCreated': {
        // Optimistic update: add new task immediately
        const existingTask = tasks.find(t => t.id === event.task.id);
        if (!existingTask) {
          set({ tasks: [...tasks, event.task] });
        }
        break;
      }

      case 'TaskClaimed': {
        // Optimistic update: update task assignment
        const updatedTasks = tasks.map(task =>
          task.id === event.taskId
            ? { ...task, assignedTo: event.assignedTo, status: 'IN_PROGRESS' as TaskStatus }
            : task
        );
        set({ tasks: updatedTasks });
        break;
      }

      case 'TaskCompleted': {
        // Optimistic update: mark task as completed
        const updatedTasks = tasks.map(task =>
          task.id === event.taskId
            ? { ...task, status: 'COMPLETED' as TaskStatus, updatedAt: event.timestamp }
            : task
        );
        set({ tasks: updatedTasks });
        break;
      }

      case 'TaskUnblocked': {
        // Optimistic update: task is now unblocked (status might change)
        const updatedTasks = tasks.map(task =>
          task.id === event.taskId
            ? { ...task, updatedAt: event.timestamp }
            : task
        );
        set({ tasks: updatedTasks });
        break;
      }

      case 'TaskFailed': {
        // Optimistic update: mark task as failed
        const updatedTasks = tasks.map(task =>
          task.id === event.taskId
            ? { ...task, status: 'FAILED' as TaskStatus, updatedAt: event.timestamp }
            : task
        );
        set({ tasks: updatedTasks });
        break;
      }

      default:
        console.warn('[TaskBoard] Unknown event type:', (event as any).type);
    }
  },

  selectTask: (taskId: string | null) => {
    set({ selectedTaskId: taskId });
  },

  disconnectStream: () => {
    if (currentAbortController) {
      try {
        currentAbortController.abort();
      } catch (e) {
        console.debug('[TaskBoard] Abort completed:', e instanceof Error ? e.message : e);
      } finally {
        currentAbortController = null;
      }
    }
  }
}));
