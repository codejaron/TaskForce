import { create } from 'zustand';
import { api } from '../../../shared/api';
import type { AgentProfile } from '../../../shared/api';

interface AgentState {
  agents: AgentProfile[];
  isLoading: boolean;
  error: string | null;
  fetchAgents: () => Promise<void>;
  createAgent: (agent: Omit<AgentProfile, 'id'>) => Promise<void>;
  updateAgent: (id: string, agent: Partial<AgentProfile>) => Promise<void>;
  deleteAgent: (id: string) => Promise<void>;
  optimizePrompt: (prompt: string) => Promise<string>;
}

export const useAgentStore = create<AgentState>((set) => ({
  agents: [],
  isLoading: false,
  error: null,

  fetchAgents: async () => {
    set({ isLoading: true, error: null });
    try {
      const rawAgents = await api.agents.list();
      console.log('[Agent Store] Fetched agents:', rawAgents);

      // Ensure agents is an array
      if (!Array.isArray(rawAgents)) {
        console.error('[Agent Store] Agents is not an array:', rawAgents);
        set({ agents: [], isLoading: false, error: 'Invalid data format from API' });
        return;
      }

      // Filter out system agents - only show WORKER type agents
      const filteredAgents = rawAgents.filter((agent: any) => {
        const roleType = agent.roleType || 'WORKER';
        return roleType === 'WORKER';
      });

      // Map backend fields to frontend fields
      const agents = filteredAgents.map((agent: any) => ({
        ...agent,
        id: String(agent.id),
        modelName: agent.model || agent.modelName, // Backend uses 'model', frontend uses 'modelName'
        providerId: agent.providerId,
        enabled: agent.status === 'ACTIVE'
      }));

      set({ agents, isLoading: false });
    } catch (error: any) {
      console.error('[Agent Store] Failed to fetch agents:', error);
      // Set empty array on error to prevent crash
      set({
        agents: [],
        isLoading: false,
        error: error.message || 'Failed to load agents'
      });
    }
  },

  createAgent: async (agent) => {
    set({ isLoading: true, error: null });
    try {
      const rawAgent = await api.agents.create(agent);
      // Map backend fields to frontend fields
      const newAgent = {
        ...rawAgent,
        id: String(rawAgent.id),
        modelName: (rawAgent as any).model || rawAgent.modelName,
        providerId: (rawAgent as any).providerId,
        enabled: (rawAgent as any).status === 'ACTIVE'
      };
      set(state => ({ agents: [...state.agents, newAgent], isLoading: false }));
    } catch (error: any) {
      set({ error: error.message, isLoading: false });
    }
  },

  updateAgent: async (id, agent) => {
    set({ isLoading: true, error: null });
    try {
      const rawAgent = await api.agents.update(id, agent);
      // Map backend fields to frontend fields
      const updatedAgent = {
        ...rawAgent,
        id: String(rawAgent.id),
        modelName: (rawAgent as any).model || rawAgent.modelName,
        providerId: (rawAgent as any).providerId,
        enabled: (rawAgent as any).status === 'ACTIVE'
      };
      set(state => ({
        agents: state.agents.map(a => a.id === id ? updatedAgent : a),
        isLoading: false
      }));
    } catch (error: any) {
      set({ error: error.message, isLoading: false });
    }
  },

  deleteAgent: async (id) => {
    set({ isLoading: true, error: null });
    try {
      await api.agents.delete(id);
      set(state => ({
        agents: state.agents.filter(a => a.id !== id),
        isLoading: false
      }));
    } catch (error: any) {
      set({ error: error.message, isLoading: false });
    }
  },

  optimizePrompt: async (prompt) => {
    try {
      const res = await api.agents.optimizePrompt(prompt);
      return res.optimized;
    } catch (error: any) {
      console.error("Failed to optimize prompt", error);
      return prompt;
    }
  }
}));
