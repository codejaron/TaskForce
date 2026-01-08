import { create } from 'zustand';
import { api } from '../../../shared/api';
import type { McpServerDefinition, ToolInfo } from '../../../shared/api';

interface McpState {
  servers: McpServerDefinition[];
  tools: ToolInfo[];
  isLoading: boolean;
  error: string | null;
  fetchServers: () => Promise<void>;
  fetchTools: () => Promise<void>;
  registerServer: (server: McpServerDefinition) => Promise<void>;
  deleteServer: (id: string) => Promise<void>;
}

export const useMcpStore = create<McpState>((set, get) => ({
  servers: [],
  tools: [],
  isLoading: false,
  error: null,

  fetchServers: async () => {
    set({ isLoading: true, error: null });
    try {
      try {
        const servers = await api.mcp.listServers();
        set({ servers, isLoading: false });
      } catch (e) {
        console.warn("Failed to fetch servers, using mock data");
        set({ 
          servers: [
            {
              id: 'filesystem',
              name: 'Local Filesystem',
              type: 'STDIO',
              command: 'npx',
              args: ['-y', '@modelcontextprotocol/server-filesystem', '.'],
              connected: true
            }
          ], 
          isLoading: false 
        });
      }
    } catch (error: any) {
      set({ error: error.message, isLoading: false });
    }
  },

  fetchTools: async () => {
    set({ isLoading: true, error: null });
    try {
      try {
        const tools = await api.mcp.listTools();
        set({ tools, isLoading: false });
      } catch (e) {
        console.warn("Failed to fetch tools, using mock data");
        set({ 
          tools: [
            { id: 'filesystem::read_file', name: 'read_file', description: 'Read file content', serverId: 'filesystem', serverName: 'Local Filesystem' },
            { id: 'filesystem::write_file', name: 'write_file', description: 'Write file content', serverId: 'filesystem', serverName: 'Local Filesystem' },
            { id: 'filesystem::list_directory', name: 'list_directory', description: 'List directory contents', serverId: 'filesystem', serverName: 'Local Filesystem' },
          ], 
          isLoading: false 
        });
      }
    } catch (error: any) {
      set({ error: error.message, isLoading: false });
    }
  },

  registerServer: async (server) => {
    set({ isLoading: true, error: null });
    try {
      await api.mcp.registerServer(server);
      await get().fetchServers();
      await get().fetchTools();
    } catch (error: any) {
      set({ error: error.message, isLoading: false });
    }
  },

  deleteServer: async (id) => {
    set({ isLoading: true, error: null });
    try {
      await api.mcp.deleteServer(id);
      set(state => ({
        servers: state.servers.filter(s => s.id !== id),
        tools: state.tools.filter(t => t.serverId !== id),
        isLoading: false
      }));
    } catch (error: any) {
      set({ error: error.message, isLoading: false });
    }
  }
}));
