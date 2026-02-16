import React, { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAgentStore } from '../../../features/agents/model/store';
import { useMcpStore } from '../../../features/mcp/model/store';
import { api } from '../../../shared/api';
import {
  Plus,
  Edit,
  Trash2,
  Bot,
  Sparkles,
  X,
  Save,
  ChevronDown,
  ChevronUp,
  Search,
  Loader2,
  Zap,
  Database,
  CheckCircle
} from 'lucide-react';
import { clsx } from 'clsx';
import type { AgentProfile, LLMProvider, ChannelModel, ToolInfo } from '../../../shared/api';

export const AgentWorkshopPage: React.FC = () => {
  const { t } = useTranslation();
  const { agents, fetchAgents, createAgent, updateAgent, deleteAgent, optimizePrompt } = useAgentStore();
  const { tools, fetchTools } = useMcpStore();

  const [isModalOpen, setIsModalOpen] = useState(false);
  const [editingAgent, setEditingAgent] = useState<Partial<AgentProfile> | null>(null);
  const [isOptimizing, setIsOptimizing] = useState(false);
  const [isSaving, setIsSaving] = useState(false);

  // Provider/Model selection
  const [providers, setProviders] = useState<LLMProvider[]>([]);
  const [selectedProviderId, setSelectedProviderId] = useState<number | null>(null);
  const [providerModels, setProviderModels] = useState<ChannelModel[]>([]);
  const [loadingModels, setLoadingModels] = useState(false);

  // Tool selection
  const [showToolSelector, setShowToolSelector] = useState(false);
  const [toolSearch, setToolSearch] = useState('');

  useEffect(() => {
    fetchAgents();
    fetchTools();
    loadProviders();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const loadProviders = async () => {
    try {
      const data = await api.llmProviders.list();
      setProviders(data);
    } catch (error) {
      console.error('Failed to load providers:', error);
    }
  };

  const loadModelsForProvider = async (providerId: number) => {
    setLoadingModels(true);
    try {
      const models = await api.llmProviders.listModels(providerId);
      setProviderModels(models || []);
    } catch (error) {
      console.error('Failed to load models:', error);
      setProviderModels([]);
    } finally {
      setLoadingModels(false);
    }
  };

  const handleProviderChange = (providerId: number) => {
    setSelectedProviderId(providerId);
    loadModelsForProvider(providerId);
    // Update providerId and reset model selection when provider changes
    if (editingAgent) {
      setEditingAgent({ ...editingAgent, providerId, modelName: '' });
    }
  };

  const handleCreate = () => {
    setEditingAgent({
      name: '',
      systemPrompt: '',
      modelName: '',
      temperature: 0.7,
      maxTokens: undefined,
      contextWindow: undefined,
      selectedMcpTools: [],
      enabled: true,
      description: ''
    });
    setSelectedProviderId(null);
    setProviderModels([]);
    setIsModalOpen(true);
  };

  const handleEdit = async (agent: AgentProfile) => {
    setEditingAgent({
      ...agent,
      selectedMcpTools: agent.selectedMcpTools || []
    });

    // Use agent's providerId directly if available
    if (agent.providerId) {
      setSelectedProviderId(agent.providerId);
      await loadModelsForProvider(agent.providerId);
    } else {
      // Fallback: try to find the provider for this agent's model
      for (const provider of providers) {
        try {
          const models = await api.llmProviders.listModels(provider.id);
          if (models?.some((m: ChannelModel) => m.modelValue === agent.modelName)) {
            setSelectedProviderId(provider.id);
            setProviderModels(models);
            break;
          }
        } catch {
          // continue to next provider
        }
      }
    }

    setIsModalOpen(true);
  };

  const handleSave = async () => {
    if (!editingAgent) return;
    setIsSaving(true);

    try {
      if (editingAgent.id) {
        await updateAgent(editingAgent.id, editingAgent);
      } else {
        await createAgent(editingAgent as Omit<AgentProfile, 'id'>);
      }
      setIsModalOpen(false);
      setEditingAgent(null);
    } catch (error) {
      console.error('Failed to save agent:', error);
    } finally {
      setIsSaving(false);
    }
  };

  const handleOptimize = async () => {
    if (!editingAgent?.systemPrompt) return;
    setIsOptimizing(true);
    try {
      const optimized = await optimizePrompt(editingAgent.systemPrompt);
      setEditingAgent((prev: Partial<AgentProfile> | null) => ({
        ...prev!,
        systemPrompt: optimized
      }));
    } catch (error) {
      console.error('Failed to optimize prompt:', error);
    } finally {
      setIsOptimizing(false);
    }
  };

  const toggleTool = (toolId: string) => {
    if (!editingAgent) return;
    const tools = editingAgent.selectedMcpTools || [];
    if (tools.includes(toolId)) {
      setEditingAgent({
        ...editingAgent,
        selectedMcpTools: tools.filter(t => t !== toolId)
      });
    } else {
      setEditingAgent({
        ...editingAgent,
        selectedMcpTools: [...tools, toolId]
      });
    }
  };

  const handleMaxTokensChange = (value: string) => {
    if (!editingAgent) return;

    if (value === '') {
      setEditingAgent({ ...editingAgent, maxTokens: undefined });
      return;
    }

    if (!/^\d+$/.test(value)) {
      return;
    }

    setEditingAgent({ ...editingAgent, maxTokens: Number(value) });
  };

  const handleContextWindowChange = (value: string) => {
    if (!editingAgent) return;

    if (value === '') {
      setEditingAgent({ ...editingAgent, contextWindow: undefined });
      return;
    }

    if (!/^\d+$/.test(value)) {
      return;
    }

    setEditingAgent({ ...editingAgent, contextWindow: Number(value) });
  };

  const filteredTools = tools.filter(
    tool =>
      tool.name.toLowerCase().includes(toolSearch.toLowerCase()) ||
      tool.description.toLowerCase().includes(toolSearch.toLowerCase())
  );

  // Group tools by server
  const toolsByServer = filteredTools.reduce((acc, tool) => {
    const server = tool.serverName || 'Unknown';
    if (!acc[server]) acc[server] = [];
    acc[server].push(tool);
    return acc;
  }, {} as Record<string, ToolInfo[]>);

  return (
    <div className="min-h-full bg-slate-50 p-8">
      <div className="max-w-7xl mx-auto">
        {/* Header */}
        <div className="flex justify-between items-start mb-8">
          <div>
            <div className="flex items-center gap-3 mb-2">
              <div className="w-10 h-10 rounded-xl bg-purple-600 flex items-center justify-center shadow-sm">
                <Bot size={20} className="text-white" />
              </div>
              <h1 className="text-3xl font-bold font-heading text-gray-900">{t('agents.title')}</h1>
            </div>
            <p className="text-gray-600">{t('dashboard.createAgentDesc')}</p>
          </div>
          <button
            onClick={handleCreate}
            className="flex items-center gap-2 bg-purple-600 hover:bg-purple-700 text-white px-6 py-3 rounded-xl font-medium transition-colors duration-200 shadow-sm cursor-pointer"
          >
            <Plus size={20} />
            {t('agents.createAgent')}
          </button>
        </div>

        {/* Agent Grid */}
        {!agents || agents.length === 0 ? (
          <div className="text-center py-20">
            <div className="w-20 h-20 mx-auto mb-6 rounded-2xl bg-purple-50 flex items-center justify-center">
              <Bot size={40} className="text-purple-600" />
            </div>
            <h3 className="text-xl font-bold font-heading text-gray-900 mb-2">{t('agents.noAgents')}</h3>
            <p className="text-gray-600 mb-6">{t('agents.createFirst')}</p>
            <button
              onClick={handleCreate}
              className="inline-flex items-center gap-2 bg-purple-600 hover:bg-purple-700 text-white px-6 py-3 rounded-xl font-medium transition-colors duration-200 cursor-pointer shadow-sm"
            >
              <Plus size={20} />
              {t('agents.createAgent')}
            </button>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
            {agents.map(agent => (
              <div
                key={agent.id}
                className="bg-white border border-gray-200 rounded-2xl overflow-hidden hover:border-gray-300 hover:shadow-md transition-all duration-200 group cursor-pointer"
              >
                <div className="p-6">
                  <div className="flex justify-between items-start mb-4">
                    <div className="flex items-center gap-4">
                      <div className="w-14 h-14 rounded-xl bg-purple-600 flex items-center justify-center shadow-sm">
                        <Bot size={28} className="text-white" />
                      </div>
                      <div>
                        <h3 className="font-bold text-xl font-heading text-gray-900">{agent.name}</h3>
                        <span className="text-xs px-2 py-0.5 rounded-full text-purple-700 bg-purple-100">
                          {agent.modelName || 'No model'}
                        </span>
                      </div>
                    </div>
                    <div className="flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity duration-200">
                      <button
                        onClick={() => handleEdit(agent)}
                        className="p-2 hover:bg-gray-100 rounded-lg text-gray-600 hover:text-gray-900 transition-colors duration-200 cursor-pointer"
                      >
                        <Edit size={16} />
                      </button>
                      <button
                        onClick={() => deleteAgent(agent.id)}
                        className="p-2 hover:bg-red-50 rounded-lg text-gray-600 hover:text-red-600 transition-colors duration-200 cursor-pointer"
                      >
                        <Trash2 size={16} />
                      </button>
                    </div>
                  </div>

                  <p className="text-gray-600 text-sm mb-4 line-clamp-2">
                    {agent.description || 'No description provided.'}
                  </p>

                  {/* MCP Tools Tags */}
                  {agent.selectedMcpTools && agent.selectedMcpTools.length > 0 && (
                    <div className="flex flex-wrap gap-2 mb-4">
                      {agent.selectedMcpTools.slice(0, 3).map((tool: string) => (
                        <span
                          key={tool}
                          className="text-xs bg-slate-50 border border-gray-200 px-2 py-1 rounded-lg text-gray-700 flex items-center gap-1"
                        >
                          <Database size={10} />
                          {tool.split('::')[1] || tool}
                        </span>
                      ))}
                      {agent.selectedMcpTools.length > 3 && (
                        <span className="text-xs bg-slate-50 border border-gray-200 px-2 py-1 rounded-lg text-gray-600">
                          +{agent.selectedMcpTools.length - 3} more
                        </span>
                      )}
                    </div>
                  )}

                  {/* Temperature and Max Tokens indicators */}
                  <div className="flex items-center gap-4 text-xs text-gray-600">
                    <div className="flex items-center gap-2">
                      <Zap size={12} />
                      <span>Temperature: {agent.temperature}</span>
                    </div>
                    <div className="flex items-center gap-2">
                      <Database size={12} />
                      <span>Max Tokens: {agent.maxTokens || 4096}</span>
                    </div>
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Edit Modal */}
      {isModalOpen && editingAgent && (
        <div className="fixed inset-0 bg-black/50 backdrop-blur-sm flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-2xl w-full max-w-3xl max-h-[90vh] overflow-hidden border border-gray-200 shadow-2xl flex flex-col">
            {/* Header */}
            <div className="p-6 border-b border-gray-200 flex justify-between items-center flex-shrink-0">
              <h2 className="text-2xl font-bold font-heading text-gray-900">
                {editingAgent.id ? t('agents.edit') + ' Agent' : t('agents.createAgent')}
              </h2>
              <button
                onClick={() => setIsModalOpen(false)}
                className="text-gray-600 hover:text-gray-900 transition-colors duration-200 cursor-pointer"
              >
                <X size={24} />
              </button>
            </div>

            {/* Content */}
            <div className="p-6 space-y-6 overflow-y-auto flex-1">
              {/* Name & Description */}
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-2">{t('agents.name')} *</label>
                  <input
                    type="text"
                    value={editingAgent.name}
                    onChange={e => setEditingAgent({ ...editingAgent, name: e.target.value })}
                    className="w-full bg-white border border-gray-300 rounded-xl px-4 py-3 text-gray-900 focus:ring-2 focus:ring-purple-500 focus:border-transparent outline-none shadow-sm"
                    placeholder={t('agents.namePlaceholder')}
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-2">{t('agents.description')}</label>
                  <input
                    type="text"
                    value={editingAgent.description}
                    onChange={e => setEditingAgent({ ...editingAgent, description: e.target.value })}
                    className="w-full bg-white border border-gray-300 rounded-xl px-4 py-3 text-gray-900 focus:ring-2 focus:ring-purple-500 focus:border-transparent outline-none shadow-sm"
                    placeholder={t('agents.descriptionPlaceholder')}
                  />
                </div>
              </div>

              {/* Provider & Model Selection */}
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-2">{t('agents.provider')}</label>
                  <select
                    value={selectedProviderId || ''}
                    onChange={e => handleProviderChange(Number(e.target.value))}
                    className="w-full bg-white border border-gray-300 rounded-xl px-4 py-3 text-gray-900 focus:ring-2 focus:ring-purple-500 focus:border-transparent outline-none shadow-sm cursor-pointer"
                  >
                    <option value="">{t('agents.selectProvider')}</option>
                    {providers.map(provider => (
                      <option key={provider.id} value={provider.id}>
                        {provider.name || provider.channelName}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-2">{t('agents.model')} *</label>
                  <div className="relative">
                    <select
                      value={editingAgent.modelName}
                      onChange={e => setEditingAgent({ ...editingAgent, modelName: e.target.value })}
                      className="w-full bg-white border border-gray-300 rounded-xl px-4 py-3 text-gray-900 focus:ring-2 focus:ring-purple-500 focus:border-transparent outline-none shadow-sm cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
                      disabled={loadingModels}
                    >
                      <option value="">{t('agents.selectModel')}...</option>
                      {providerModels.map(model => (
                        <option key={model.modelValue} value={model.modelValue}>
                          {model.displayName || model.modelValue}
                        </option>
                      ))}
                    </select>
                    {loadingModels && (
                      <div className="absolute right-3 top-1/2 -translate-y-1/2">
                        <Loader2 size={16} className="animate-spin text-gray-400" />
                      </div>
                    )}
                  </div>
                  {!selectedProviderId && (
                    <p className="text-xs text-gray-500 mt-1">Select a provider first</p>
                  )}
                </div>
              </div>

              {/* Temperature */}
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">
                  Temperature: {editingAgent.temperature}
                </label>
                <input
                  type="range"
                  min="0"
                  max="2"
                  step="0.1"
                  value={editingAgent.temperature}
                  onChange={e => setEditingAgent({ ...editingAgent, temperature: parseFloat(e.target.value) })}
                  className="w-full h-2 bg-gray-200 rounded-lg appearance-none cursor-pointer accent-purple-600"
                />
                <div className="flex justify-between text-xs text-gray-500 mt-1">
                  <span>Precise (0)</span>
                  <span>Creative (2)</span>
                </div>
              </div>

              {/* Max Tokens */}
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">
                  {t('agents.maxTokens')}
                  {editingAgent.maxTokens != null ? `: ${editingAgent.maxTokens}` : ''}
                </label>
                <input
                  type="text"
                  inputMode="numeric"
                  pattern="[0-9]*"
                  value={editingAgent.maxTokens != null ? String(editingAgent.maxTokens) : ''}
                  onChange={e => handleMaxTokensChange(e.target.value)}
                  placeholder="4096"
                  className="w-full px-3 py-2 bg-white border border-gray-300 rounded-lg text-gray-900 placeholder-gray-400 focus:outline-none focus:border-purple-500 focus:ring-2 focus:ring-purple-500 shadow-sm"
                />
                <p className="text-xs text-gray-500 mt-1">{t('agents.maxTokensHint')}</p>
              </div>

              {/* Context Window */}
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">
                  {t('agents.contextWindow')}
                  {editingAgent.contextWindow != null ? `: ${editingAgent.contextWindow}` : ''}
                </label>
                <input
                  type="text"
                  inputMode="numeric"
                  pattern="[0-9]*"
                  value={editingAgent.contextWindow != null ? String(editingAgent.contextWindow) : ''}
                  onChange={e => handleContextWindowChange(e.target.value)}
                  placeholder="32000"
                  className="w-full px-3 py-2 bg-white border border-gray-300 rounded-lg text-gray-900 placeholder-gray-400 focus:outline-none focus:border-purple-500 focus:ring-2 focus:ring-purple-500 shadow-sm"
                />
                <p className="text-xs text-gray-500 mt-1">{t('agents.contextWindowHint')}</p>
              </div>

              {/* System Prompt */}
              <div>
                <div className="flex justify-between items-center mb-2">
                  <label className="block text-sm font-medium text-gray-700">{t('agents.systemPromptLabel')} *</label>
                  <button
                    onClick={handleOptimize}
                    disabled={isOptimizing || !editingAgent.systemPrompt}
                    className="text-xs flex items-center gap-1 text-purple-600 hover:text-purple-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors duration-200 cursor-pointer"
                  >
                    {isOptimizing ? (
                      <Loader2 size={12} className="animate-spin" />
                    ) : (
                      <Sparkles size={12} />
                    )}
                    {isOptimizing ? t('common.loading') : t('agents.optimizeWithAI')}
                  </button>
                </div>
                <textarea
                  value={editingAgent.systemPrompt}
                  onChange={e => setEditingAgent({ ...editingAgent, systemPrompt: e.target.value })}
                  className="w-full h-40 bg-white border border-gray-300 rounded-xl px-4 py-3 text-gray-900 focus:ring-2 focus:ring-purple-500 focus:border-transparent outline-none resize-none font-mono text-sm shadow-sm"
                  placeholder={t('agents.systemPromptPlaceholder')}
                />
              </div>

              {/* MCP Tools */}
              <div>
                <div className="flex justify-between items-center mb-3">
                  <label className="block text-sm font-medium text-gray-700">
                    {t('agents.mcpTools')} ({editingAgent.selectedMcpTools?.length || 0} {t('agents.selected')})
                  </label>
                  <button
                    onClick={() => setShowToolSelector(!showToolSelector)}
                    className="text-sm text-purple-600 hover:text-purple-700 flex items-center gap-1 cursor-pointer transition-colors duration-200"
                  >
                    {showToolSelector ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
                    {showToolSelector ? t('common.cancel') : t('agents.selectTools')}
                  </button>
                </div>

                {/* Selected Tools */}
                {editingAgent.selectedMcpTools && editingAgent.selectedMcpTools.length > 0 && (
                  <div className="flex flex-wrap gap-2 mb-3">
                    {editingAgent.selectedMcpTools.map(toolId => {
                      const tool = tools.find(t => t.id === toolId);
                      return (
                        <span
                          key={toolId}
                          className="flex items-center gap-1 px-3 py-1.5 bg-purple-50 border border-purple-200 rounded-lg text-sm text-purple-700"
                        >
                          <CheckCircle size={12} />
                          {tool?.name || toolId}
                          <button
                            onClick={() => toggleTool(toolId)}
                            className="ml-1 hover:text-red-600 transition-colors duration-200 cursor-pointer"
                          >
                            <X size={12} />
                          </button>
                        </span>
                      );
                    })}
                  </div>
                )}

                {/* Tool Selector */}
                {showToolSelector && (
                  <div className="bg-slate-50 border border-gray-200 rounded-xl p-4 max-h-60 overflow-y-auto">
                    {/* Search */}
                    <div className="relative mb-3">
                      <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
                      <input
                        type="text"
                        value={toolSearch}
                        onChange={e => setToolSearch(e.target.value)}
                        placeholder={t('mcp.searchTools')}
                        className="w-full bg-white border border-gray-300 rounded-lg pl-10 pr-4 py-2 text-sm text-gray-900 focus:ring-2 focus:ring-purple-500 focus:border-transparent outline-none shadow-sm"
                      />
                    </div>

                    {/* Tools by Server */}
                    {Object.entries(toolsByServer).map(([server, serverTools]) => (
                      <div key={server} className="mb-4 last:mb-0">
                        <h4 className="text-xs font-medium text-gray-600 mb-2 flex items-center gap-1">
                          <Database size={12} />
                          {server}
                        </h4>
                        <div className="space-y-1">
                          {serverTools.map(tool => (
                            <label
                              key={tool.id}
                              className={clsx(
                                "flex items-start gap-3 p-2 rounded-lg cursor-pointer transition-colors duration-200",
                                editingAgent.selectedMcpTools?.includes(tool.id)
                                  ? "bg-purple-50 border border-purple-200"
                                  : "hover:bg-white border border-transparent"
                              )}
                            >
                              <input
                                type="checkbox"
                                checked={editingAgent.selectedMcpTools?.includes(tool.id)}
                                onChange={() => toggleTool(tool.id)}
                                className="mt-0.5 w-4 h-4 rounded border-gray-300 bg-white text-purple-600 focus:ring-purple-500 cursor-pointer"
                              />
                              <div className="flex-1 min-w-0">
                                <div className="text-sm text-gray-900 font-medium">{tool.name}</div>
                                <div className="text-xs text-gray-600 truncate">{tool.description}</div>
                              </div>
                            </label>
                          ))}
                        </div>
                      </div>
                    ))}

                    {Object.keys(toolsByServer).length === 0 && (
                      <p className="text-center text-gray-500 text-sm py-4">
                        {toolSearch ? 'No matching tools found' : 'No tools available. Connect MCP servers first.'}
                      </p>
                    )}
                  </div>
                )}
              </div>
            </div>

            {/* Footer */}
            <div className="p-6 border-t border-gray-200 flex justify-end gap-3 flex-shrink-0">
              <button
                onClick={() => setIsModalOpen(false)}
                className="px-6 py-2.5 text-gray-700 hover:text-gray-900 hover:bg-gray-100 rounded-xl transition-colors duration-200 cursor-pointer"
              >
                {t('common.cancel')}
              </button>
              <button
                onClick={handleSave}
                disabled={isSaving || !editingAgent.name || !editingAgent.modelName}
                className="px-6 py-2.5 bg-purple-600 hover:bg-purple-700 text-white rounded-xl transition-colors duration-200 font-medium disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2 cursor-pointer shadow-sm"
              >
                {isSaving && <Loader2 size={16} className="animate-spin" />}
                <Save size={16} />
                {t('agents.save')} Agent
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
