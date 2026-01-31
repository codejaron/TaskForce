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
  type: 'SINGLE' | 'GROUP';
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

// ==================== 新增：异步工作流相关类型 ====================

export interface SubmitResponse {
  requestId: string;
  status: 'PROCESSING' | 'RESUMED' | 'PAUSED' | 'ERROR';
}

export interface WorkflowStateResponse {
  planId: string;
  sessionId: string;
  goal: string;
  status: string;
  currentPhase: string;
  currentStepIndex: number;
  totalSteps: number;
  completedSteps: number;
  pauseReason?: string;
  pendingQuestion?: string;
  steps: StepSummary[];
}

export interface StepSummary {
  stepId: string;
  stepIndex: number;
  description: string;
  status: string;
  assignedAgentName: string;
}

export interface A2AMessage {
  agentId: string;
  agentName: string;
  content: string;
  rawContent?: string;
  timestamp: string;
  type: 'text' | 'tool_use' | 'tool_result' | 'plan' | 'question';

  // 新增字段：步骤和计划信息
  stepId?: string;
  /**
   * 原始 stepId（当 stepId 因去重被追加后缀时，用它来与后续事件/tool call 关联）
   */
  originalStepId?: string;
  stepIndex?: number;
  stepDescription?: string;
  planId?: string;
  goal?: string;
  
  // 新增字段：流式状态
  isStreaming?: boolean;
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
  toolArgs: string;
  toolResult?: string;
  status: 'RUNNING' | 'SUCCESS' | 'FAILED';
  errorMessage?: string;
  durationMs?: number;
  stepId?: string;
  sequence: number;
}
