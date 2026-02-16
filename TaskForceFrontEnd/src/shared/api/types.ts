export interface AgentProfile {
  id: string;
  name: string;
  systemPrompt: string;
  modelName: string;
  model?: string;  // Backend uses 'model' instead of 'modelName'
  providerId?: number;  // LLM Provider ID for model selection
  temperature: number;
  maxTokens?: number;  // Maximum tokens for generation
  selectedMcpTools?: string[];  // Optional since backend doesn't have this field yet
  description?: string;
  roleType?: string; // MODERATOR/WORKER
  enabled: boolean;
}

export interface ToolInfo {
  id: string;
  name: string;
  description: string;
  serverId: string;
  serverName: string;
  inputSchema?: string;  // 添加可选字段，与后端保持一致
}

export interface AgentToolDetail {
  id: string;
  name: string;
  description: string;
  serverId?: string;
  serverName?: string;
  inputSchema?: string;
  addedAt: string;  // ISO date string
  enabled: boolean;
}

export interface McpServerDefinition {
  id?: string;
  name: string;
  type: 'STDIO' | 'SSE';
  command?: string;
  args?: string[];
  sseUrl?: string;
  connected?: boolean;
}

export interface Model {
  id: string;
  name: string;
  provider: string;
  description?: string;
}

export interface ChannelModel {
  id?: number;
  modelValue: string; // API model identifier, e.g. gpt-4o
  displayName: string; // user-friendly name
  createdAt?: string;
}

export interface Session {
  id: string;
  name: string;
  type: 'SINGLE' | 'GROUP' | 'TEAM' | 'CHAT';
  status: string;
  currentRound: number;
  createdAt: string;
  updatedAt: string;
}

export interface Message {
  id: number;
  sessionId: string;
  agentId?: number;
  agentName?: string;
  content: string;
  messageType: string; // text/tool_use/tool_result
  role: string; // user/assistant/system
  toolName?: string;
  toolArgs?: string;
  toolResult?: string;
  stepId?: string;
  sequence: number;
  status?: 'STREAMING' | 'COMPLETED';  // 消息状态：流式输出中或已完成
  createdAt: string;
}

export interface LLMProvider {
  id: number;
  name?: string;
  type?: string;
  baseUrl?: string;

  // front-end-friendly aliases (kept for compatibility)
  channelName?: string;
  providerType?: 'OPENAI' | 'AZURE_OPENAI' | 'DEEPSEEK' | 'OLLAMA' | 'CUSTOM';

  apiKey?: string;
  config?: string; // JSON
  temperature?: number;
  maxTokens?: number;
  enabled?: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface LLMProviderRequest {
  name: string;
  type: string; // OPENAI/AZURE_OPENAI/DEEPSEEK/OLLAMA/CUSTOM
  baseUrl?: string; // optional — only send if explicitly provided
  apiKey?: string;
  config?: string;
  models?: ChannelModel[];
}

// ============= Token统计相关类型 =============

export interface ProviderCostDTO {
  providerId: number;
  providerName: string;
  totalCost: number;
  totalTokens: number;
  callCount: number;
}

export interface ModelUsageDTO {
  modelName: string;
  totalPromptTokens: number;
  totalCompletionTokens: number;
  totalTokens: number;
  totalCost: number;
  callCount: number;
}

export interface DailyCostDTO {
  date: string; // ISO date string
  totalCost: number;
  totalTokens: number;
  callCount: number;
}

export interface SessionCostDTO {
  sessionId: string;
  sessionName: string;
  totalCost: number;
  totalTokens: number;
  callCount: number;
}

export interface AgentUsageDTO {
  agentId: number;
  agentName: string;
  totalPromptTokens: number;
  totalCompletionTokens: number;
  totalTokens: number;
  totalCost: number;
  callCount: number;
}

export interface AgentCostDTO {
  agentId: number;
  agentName: string;
  totalCost: number;
  totalTokens: number;
  callCount: number;
}

// ============= 工具调用记录类型 =============

export interface ToolCallDTO {
  toolCallId: string;
  toolName: string;
  serverName?: string;  // MCP Server 名称
  instanceId?: string;
  toolArgs: string;
  toolResult?: string;
  status: 'RUNNING' | 'SUCCESS' | 'FAILED';
  errorMessage?: string;
  durationMs?: number;
  stepId?: string;
  sequence: number;
  startedAt?: string;
  completedAt?: string;
}

// ============= Team API 相关类型 =============

export interface TeamHistoryMessageDTO {
  id: number;
  role: 'user' | 'assistant' | 'system';
  messageType: 'TEAM_USER' | 'TEAM_LEAD' | 'TEAM_WORKER' | 'TEAM_SYSTEM';
  agentName?: string;
  content: string;
  createdAt: string;
}

export interface TeamHistoryToolCallDTO {
  toolCallId: string;
  stepId?: string;
  sequence?: number;
  instanceId?: string;
  toolName: string;
  serverName?: string;
  toolArgs?: string;
  toolResult?: string;
  status: 'RUNNING' | 'SUCCESS' | 'FAILED';
  errorMessage?: string;
  durationMs?: number;
  startedAt?: string;
  completedAt?: string;
}

export interface TeamSessionHistoryDTO {
  messages: TeamHistoryMessageDTO[];
  toolCalls: TeamHistoryToolCallDTO[];
  nextBefore: string | null;
}

export interface TeamStartRequest {
  sessionId: string;
  userGoal: string;
}

export interface TeamMessage {
  message: string;
}

export type TaskStatus = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED';

export interface Task {
  id: string;
  title: string;
  description?: string;
  status: TaskStatus;
  assignedTo?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface TaskBoard {
  sessionId: string;
  tasks: Task[];
}

export type WorkerStatus = 'IDLE' | 'WORKING' | 'WAITING' | 'WAITING_REPLY' | 'SHUTDOWN';
export type RuntimeLifecycleStatus = 'RUNNING' | 'STOPPED' | 'DESTROYED';

export interface WorkerInstance {
  instanceId: string;
  workerId?: number;
  agentName: string;
  status: WorkerStatus;
  currentTaskId?: number;
  loopRunning?: boolean;
  lifecycleStatus?: RuntimeLifecycleStatus;
  startedAt?: string;
  updatedAt?: string;
}

export interface TeamRuntimeStatus {
  leadLifecycleStatus: RuntimeLifecycleStatus;
  leadLoopRunning: boolean;
  leadExecutionStatus?: string | null;
  workers: WorkerInstance[];
}
