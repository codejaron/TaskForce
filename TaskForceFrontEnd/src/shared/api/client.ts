import type { AgentProfile, McpServerDefinition, ToolInfo, AgentToolDetail, Session, LLMProvider, ChannelModel, Message, SubmitResponse, WorkflowStateResponse, ProviderCostDTO, ModelUsageDTO, DailyCostDTO, SessionCostDTO, AgentUsageDTO, AgentCostDTO, ToolCallDTO } from './types';

const API_BASE = '/api';

// Backend ApiResponse format: { code: number, message: string, data?: T }
interface ApiResponse<T> {
  code: number;
  message: string;
  data?: T;
}

async function fetchJson<T>(url: string, options?: RequestInit): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...options?.headers as Record<string, string>,
  };

  const res = await fetch(`${API_BASE}${url}`, {
    ...options,
    headers,
  });

  if (!res.ok) {
    throw new Error(`API Error: ${res.statusText}`);
  }

  const json = await res.json();

  // Handle ApiResponse wrapper from backend
  if (json && typeof json === 'object' && 'code' in json && 'message' in json) {
    const apiResponse = json as ApiResponse<T>;
    if (apiResponse.code !== 200) {
      throw new Error(apiResponse.message || 'API request failed');
    }
    return apiResponse.data as T;
  }

  // Fallback for direct responses (legacy endpoints like /sessions)
  return json as T;
}

export const api = {
  agents: {
    list: () => fetchJson<AgentProfile[]>('/agents'),
    listAll: () => fetchJson<AgentProfile[]>('/agents/all'),
    get: (id: string) => fetchJson<AgentProfile>(`/agents/${id}`),
    create: (data: Omit<AgentProfile, 'id'>) => fetchJson<AgentProfile>('/agents', { method: 'POST', body: JSON.stringify(data) }),
    update: (id: string, data: Partial<AgentProfile>) => fetchJson<AgentProfile>(`/agents/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    delete: (id: string) => fetchJson<{success: boolean}>(`/agents/${id}`, { method: 'DELETE' }),
    optimizePrompt: (prompt: string) => fetchJson<{optimized: string}>('/agents/optimize-prompt', { method: 'POST', body: JSON.stringify({ prompt }) }),
  },
  mcp: {
    listTools: () => fetchJson<ToolInfo[]>('/mcp/tools'),
    listServers: () => fetchJson<McpServerDefinition[]>('/mcp/servers'),
    registerServer: (data: McpServerDefinition) => fetchJson<{success: boolean}>('/mcp/servers', { method: 'POST', body: JSON.stringify(data) }),
    deleteServer: (id: string) => fetchJson<{success: boolean}>(`/mcp/servers/${id}`, { method: 'DELETE' }),
  },
  agentTools: {
    getAgentTools: (agentId: string) => fetchJson<AgentToolDetail[]>(`/agents/${agentId}/tools`),
    setAgentTools: (agentId: string, toolIds: string[]) => fetchJson<void>(`/agents/${agentId}/tools`, { method: 'PUT', body: JSON.stringify({ toolIds }) }),
    addTool: (agentId: string, toolId: string) => fetchJson<void>(`/agents/${agentId}/tools`, { method: 'POST', body: JSON.stringify({ toolId }) }),
    removeTool: (agentId: string, toolId: string) => fetchJson<void>(`/agents/${agentId}/tools/${toolId}`, { method: 'DELETE' }),
    toggleTool: (agentId: string, toolId: string, enabled: boolean) => fetchJson<void>(`/agents/${agentId}/tools/${toolId}`, { method: 'PATCH', body: JSON.stringify({ enabled }) }),
    cleanupInvalidTools: (agentId: string) => fetchJson<{cleanedCount: number}>(`/agents/${agentId}/tools/cleanup`, { method: 'POST' }),
  },
  sessions: {
    list: () => fetchJson<Session[]>('/sessions'),
    create: (data: { name: string; type: string; agentIds: number[] }) =>
      fetchJson<Session>('/sessions/create', { method: 'POST', body: JSON.stringify(data) }),
    stop: (sessionId: string) =>
      fetchJson<{success: boolean}>(`/sessions/${sessionId}/stop`, { method: 'POST' }),
    delete: (sessionId: string) =>
      fetchJson<void>(`/sessions/${sessionId}`, { method: 'DELETE' }),
  },
  messages: {
    getBySession: (sessionId: string) => fetchJson<Message[]>(`/messages/session/${sessionId}`),
  },
  toolCalls: {
    getBySession: (sessionId: string) => fetchJson<ToolCallDTO[]>(`/tool-calls/session/${sessionId}`),
    getByStep: (stepId: string) => fetchJson<ToolCallDTO[]>(`/tool-calls/step/${stepId}`),
  },
  remoteModels: {
    fetch: (baseUrl: string, apiKey?: string) => fetchJson<{modelValue: string; displayName: string}[]>('/providers/fetch-models', { method: 'POST', body: JSON.stringify({ baseUrl, apiKey }) }),
    fetchForProvider: (providerId: number) => fetchJson<{modelValue: string; displayName: string}[]>(`/providers/${providerId}/fetch-remote`),
  },
  llmProviders: {
    list: () => fetchJson<LLMProvider[]>('/providers'),
    get: (id: number) => fetchJson<LLMProvider>(`/providers/${id}`),
    create: (data: Partial<LLMProvider>) => fetchJson<LLMProvider>('/providers', { method: 'POST', body: JSON.stringify(data) }),
    update: (id: number, data: Partial<LLMProvider>) => fetchJson<LLMProvider>(`/providers/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
    delete: (id: number) => fetchJson<{success: boolean}>(`/providers/${id}`, { method: 'DELETE' }),
    // channel models
    listModels: (providerId: number) => fetchJson<ChannelModel[]>(`/providers/${providerId}/models`),
    createModel: (providerId: number, model: ChannelModel) => fetchJson<ChannelModel>(`/providers/${providerId}/models`, { method: 'POST', body: JSON.stringify(model) }),
    deleteModel: (providerId: number, modelId: number) => fetchJson<{success: boolean}>(`/providers/${providerId}/models/${modelId}`, { method: 'DELETE' }),
    replaceModels: (providerId: number, models: ChannelModel[]) => fetchJson<ChannelModel[]>(`/providers/${providerId}/models`, { method: 'PUT', body: JSON.stringify(models) }),
  },
  groupChat: {
    // 新增：统一消息接口（后端自动判断 submit 还是 resume）
    message: (sessionId: string, text: string) =>
      fetchJson<SubmitResponse>(`/group-chat/${sessionId}/message`, {
        method: 'POST',
        body: JSON.stringify({ text })
      }),
    // 保留旧接口（可选，后续可删除）
    submit: (sessionId: string, text: string) =>
      fetchJson<SubmitResponse>(`/group-chat/${sessionId}/submit`, {
        method: 'POST',
        body: JSON.stringify({ text })
      }),
    resume: (sessionId: string, answer: string) =>
      fetchJson<SubmitResponse>(`/group-chat/${sessionId}/resume`, {
        method: 'POST',
        body: JSON.stringify({ text: answer })
      }),
    getState: (sessionId: string) =>
      fetchJson<WorkflowStateResponse>(`/group-chat/${sessionId}/state`),
    stop: (sessionId: string) =>
      fetchJson<void>(`/group-chat/${sessionId}/stop`, {
        method: 'POST'
      }),
  },
  tokenUsage: {
    getProviderCost: (startDate: string, endDate: string) =>
      fetchJson<ProviderCostDTO[]>(`/token-usage/provider-cost?startDate=${startDate}&endDate=${endDate}`),

    getTopModels: (startDate: string, endDate: string, limit: number = 10) =>
      fetchJson<ModelUsageDTO[]>(`/token-usage/top-models?startDate=${startDate}&endDate=${endDate}&limit=${limit}`),

    getDailyCostTrend: (startDate: string, endDate: string) =>
      fetchJson<DailyCostDTO[]>(`/token-usage/daily-cost?startDate=${startDate}&endDate=${endDate}`),

    getTopSessions: (startDate: string, endDate: string, limit: number = 10) =>
      fetchJson<SessionCostDTO[]>(`/token-usage/top-sessions?startDate=${startDate}&endDate=${endDate}&limit=${limit}`),

    getTopAgents: (startDate: string, endDate: string, limit: number = 10) =>
      fetchJson<AgentUsageDTO[]>(`/token-usage/top-agents?startDate=${startDate}&endDate=${endDate}&limit=${limit}`),

    getTopAgentsCost: (startDate: string, endDate: string, limit: number = 10) =>
      fetchJson<AgentCostDTO[]>(`/token-usage/top-agents-cost?startDate=${startDate}&endDate=${endDate}&limit=${limit}`),
  }
 };
