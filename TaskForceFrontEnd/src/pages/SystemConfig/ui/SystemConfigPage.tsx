import { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { Settings, Loader2, AlertCircle, CheckCircle } from 'lucide-react';
import { api } from '../../../shared/api';
import type { AgentProfile, LLMProvider, ChannelModel } from '../../../shared/api/types';

// Extend AgentProfile type for system agents where roleType and providerId are required
interface SystemAgentProfile extends AgentProfile {
  roleType: string;
  providerId?: number;
}

interface SystemAgent {
  id: string;
  roleType: string;
  name: string;
  displayName: string;
  providerId?: number;
  model?: string;
  temperature?: number;
  maxTokens?: number;
}

interface SystemAgentGroup {
  groupType: string; // Store the role type for translation lookup
  agents: SystemAgent[];
  providerId?: number;
  model?: string;
  temperature?: number;
  maxTokens?: number;
}

const SYSTEM_AGENT_TYPES: Record<string, string> = {
  'PLANNER': 'PLANNER'
};

const DEFAULT_SYSTEM_AGENT_NAMES: Record<string, string> = {
  'PLANNER': 'Planner Agent'
};

function extractErrorMessage(err: unknown, fallback: string): string {
  if (err instanceof Error && err.message) {
    return err.message;
  }
  return fallback;
}

// Helper function to get display name, description, and placeholder for system agent groups
function getAgentGroupInfo(roleType: string, t: ReturnType<typeof useTranslation>['t']) {
  switch (roleType) {
    case 'PLANNER':
      return {
        displayName: t('systemConfig.planner', 'Planner Agent'),
        description: t('systemConfig.plannerDesc', 'Plans and orchestrates task execution'),
        modelPlaceholder: 'e.g., gpt-4o'
      };
    default:
      return { displayName: roleType, description: '', modelPlaceholder: 'e.g., gpt-4o' };
  }
}

export function SystemConfigPage() {
  const { t } = useTranslation();
  const [agentGroups, setAgentGroups] = useState<SystemAgentGroup[]>([]);
  const [providers, setProviders] = useState<LLMProvider[]>([]);
  const [providerModels, setProviderModels] = useState<Record<number, ChannelModel[]>>({});
  const [loadingModels, setLoadingModels] = useState<Record<number, boolean>>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState<Record<string, boolean>>({});
  const [successMessage, setSuccessMessage] = useState<Record<string, boolean>>({});
  const [error, setError] = useState<string | null>(null);

  const loadModelsForProvider = useCallback(async (providerId: number) => {
    if (!providerId || providerModels[providerId] !== undefined) {
      return;
    }
    setLoadingModels(prev => ({ ...prev, [providerId]: true }));
    try {
      const models = await api.llmProviders.listModels(providerId);
      setProviderModels(prev => ({ ...prev, [providerId]: models || [] }));
    } catch (err) {
      console.error('Failed to load models for provider:', providerId, err);
      setProviderModels(prev => ({ ...prev, [providerId]: [] }));
    } finally {
      setLoadingModels(prev => ({ ...prev, [providerId]: false }));
    }
  }, [providerModels]);

  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // Load all agents (include system agents)
      const agents = await api.agents.listAll();

      // Filter system agents
      const filtered = agents
        .filter((agent: AgentProfile) => {
          const roleType = (agent as SystemAgentProfile).roleType || 'WORKER';
          return Object.keys(SYSTEM_AGENT_TYPES).includes(roleType);
        })
        .map((agent: AgentProfile) => {
          const roleType = (agent as SystemAgentProfile).roleType || 'WORKER';
          return {
            id: agent.id,
            roleType: roleType,
            name: agent.name,
            displayName: SYSTEM_AGENT_TYPES[roleType] || roleType,
            providerId: (agent as SystemAgentProfile).providerId,
            model: agent.model || agent.modelName,  // Backend uses 'model', fallback to 'modelName'
            temperature: agent.temperature,
            maxTokens: agent.maxTokens
          };
        });

      // Ensure every system role has one config group.
      // If DB has no record yet, keep an empty agent list and allow save to create.
      const groups: SystemAgentGroup[] = Object.keys(SYSTEM_AGENT_TYPES).map((roleType) => {
        const matchedAgents = filtered.filter(agent => agent.roleType === roleType);
        const primaryAgent = matchedAgents[0];

        return {
          groupType: roleType,
          agents: matchedAgents,
          providerId: primaryAgent?.providerId,
          model: primaryAgent?.model,
          temperature: primaryAgent?.temperature ?? 0.3,
          maxTokens: primaryAgent?.maxTokens
        };
      });

      setAgentGroups(groups);

      // Load providers
      const providersList = await api.llmProviders.list();
      setProviders(providersList);
    } catch (err) {
      console.error('Failed to load system agents:', err);
      setError(t('systemConfig.failedToSave'));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  useEffect(() => {
    const providerIds = Array.from(
      new Set(
        agentGroups
          .map(group => group.providerId)
          .filter((providerId): providerId is number => typeof providerId === 'number')
      )
    );
    providerIds.forEach((providerId) => {
      if (providerModels[providerId] === undefined) {
        void loadModelsForProvider(providerId);
      }
    });
  }, [agentGroups, providerModels, loadModelsForProvider]);

  const handleSave = async (groupIndex: number, providerId: number | undefined, model: string | undefined) => {
    const groupKey = `group-${groupIndex}`;
    setSaving(prev => ({ ...prev, [groupKey]: true }));
    setSuccessMessage(prev => ({ ...prev, [groupKey]: false }));

    try {
      const group = agentGroups[groupIndex];
      if (!group) {
        throw new Error('Agent group not found');
      }

      // 已存在系统 Agent：批量更新；不存在：创建一条系统 Agent
      if (group.agents.length > 0) {
        await Promise.all(
          group.agents.map(agent =>
            api.agents.update(agent.id, {
              id: agent.id,
              name: agent.name,
              providerId: providerId,
              model,
              temperature: group.temperature,
              maxTokens: group.maxTokens
            } as Partial<AgentProfile>)
          )
        );
      } else {
        try {
          await api.agents.create({
            name: DEFAULT_SYSTEM_AGENT_NAMES[group.groupType] || group.groupType,
            roleType: group.groupType,
            providerId: providerId,
            model,
            temperature: group.temperature,
            maxTokens: group.maxTokens,
            systemPrompt: ''
          } as unknown as Omit<AgentProfile, 'id'>);
        } catch (createErr) {
          // Defensive fallback: if another write path created the system agent concurrently,
          // reload and switch to update instead of failing the user action.
          const latestAgents = await api.agents.listAll();
          const existingSystemAgent = latestAgents.find(agent =>
            ((agent as SystemAgentProfile).roleType || 'WORKER') === group.groupType
          );
          if (!existingSystemAgent) {
            throw createErr;
          }
          await api.agents.update(existingSystemAgent.id, {
            id: existingSystemAgent.id,
            name: existingSystemAgent.name,
            providerId: providerId,
            model,
            temperature: group.temperature,
            maxTokens: group.maxTokens
          } as Partial<AgentProfile>);
        }
      }

      // 重新加载数据以获取最新的配置（包括后端自动测试的 embedding dimension）
      await loadData();

      // 显示成功提示
      setSuccessMessage(prev => ({ ...prev, [groupKey]: true }));
      setTimeout(() => {
        setSuccessMessage(prev => ({ ...prev, [groupKey]: false }));
      }, 2000);
    } catch (err) {
      console.error('Failed to save agent configuration:', err);
      setError(extractErrorMessage(err, t('systemConfig.failedToSave')));
    } finally {
      setSaving(prev => ({ ...prev, [groupKey]: false }));
    }
  };

  if (loading) {
    return (
      <div className="p-8 flex items-center justify-center min-h-screen">
        <Loader2 className="w-8 h-8 animate-spin text-blue-400" />
      </div>
    );
  }

  return (
    <div className="p-8 h-screen overflow-y-auto bg-slate-50">
      {/* Header */}
      <div className="mb-8">
        <div className="flex items-center gap-3 mb-4">
          <Settings className="w-8 h-8 text-blue-600" />
          <h1 className="text-3xl font-bold font-heading text-gray-900">{t('systemConfig.title')}</h1>
        </div>
        <p className="text-gray-600">{t('systemConfig.description')}</p>
      </div>

      {/* Error Message */}
      {error && (
        <div className="mb-6 p-4 bg-red-50 border border-red-200 rounded-lg flex items-center gap-3 text-red-700">
          <AlertCircle className="w-5 h-5 flex-shrink-0" />
          <span>{error}</span>
        </div>
      )}

      {/* System Agents Grid */}
      <div className="grid gap-6 pb-8">
        {agentGroups.length === 0 ? (
          <div className="p-8 bg-white border border-gray-200 rounded-lg text-center text-gray-600 shadow-sm">
            {t('systemConfig.noAgentsConfigured')}
          </div>
        ) : (
          agentGroups.map((group, groupIndex) => {
            const groupKey = `group-${groupIndex}`;
            const info = getAgentGroupInfo(group.groupType, t);
            return (
              <div
                key={groupIndex}
                className="p-6 bg-white border border-gray-200 rounded-lg shadow-sm"
              >
                <div className="mb-6">
                  <h3 className="text-lg font-semibold font-heading text-gray-900 mb-1">
                    {info.displayName}
                  </h3>
                  <p className="text-sm text-gray-600">
                    {info.description}
                  </p>
                </div>

                <div className="grid grid-cols-2 gap-4">
                  {/* Provider Selection */}
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-2">
                      {t('systemConfig.llmProvider')}
                    </label>
                    <select
                      value={group.providerId || ''}
                      onChange={(e) => {
                        const providerId = e.target.value ? parseInt(e.target.value) : undefined;
                        if (providerId) {
                          void loadModelsForProvider(providerId);
                        }
                        setAgentGroups(prev => prev.map((g, idx) =>
                          idx === groupIndex ? { ...g, providerId, model: undefined } : g
                        ));
                      }}
                      className="w-full px-3 py-2 bg-white border border-gray-300 rounded-lg text-gray-900 placeholder-gray-400 focus:outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-500 shadow-sm cursor-pointer"
                    >
                      <option value="">{t('systemConfig.selectProvider')}</option>
                      {providers.map((provider) => (
                        <option key={provider.id} value={provider.id}>
                          {provider.name}
                        </option>
                      ))}
                    </select>
                  </div>

                  {/* Model Selection */}
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-2">
                      {t('systemConfig.modelName')}
                    </label>
                    <select
                      value={group.model || ''}
                      onChange={(e) => {
                        setAgentGroups(prev => prev.map((g, idx) =>
                          idx === groupIndex ? { ...g, model: e.target.value || undefined } : g
                        ));
                      }}
                      disabled={!group.providerId || loadingModels[group.providerId]}
                      className="w-full px-3 py-2 bg-white border border-gray-300 rounded-lg text-gray-900 placeholder-gray-400 focus:outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-500 shadow-sm cursor-pointer disabled:cursor-not-allowed disabled:bg-gray-50 disabled:text-gray-400"
                    >
                      <option value="">
                        {!group.providerId
                          ? t('agents.selectModelFirst')
                          : loadingModels[group.providerId]
                            ? t('common.loading')
                            : t('agents.selectModel')}
                      </option>
                      {group.providerId && providerModels[group.providerId]?.map((model) => (
                        <option key={model.modelValue} value={model.modelValue}>
                          {model.displayName || model.modelValue}
                        </option>
                      ))}
                    </select>
                  </div>

                  {/* Temperature */}
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-2">
                      {t('agents.temperature')}
                    </label>
                    <input
                      type="number"
                      min="0"
                      max="2"
                      step="0.1"
                      value={group.temperature ?? ''}
                      onChange={(e) => {
                        const value = e.target.value;
                        const parsed = value === '' ? undefined : parseFloat(value);
                        setAgentGroups(prev => prev.map((g, idx) =>
                          idx === groupIndex ? { ...g, temperature: Number.isNaN(parsed as number) ? undefined : parsed } : g
                        ));
                      }}
                      className="w-full px-3 py-2 bg-white border border-gray-300 rounded-lg text-gray-900 placeholder-gray-400 focus:outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-500 shadow-sm"
                    />
                  </div>

                  {/* Max Tokens */}
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-2">
                      {t('agents.maxTokens')}
                    </label>
                    <input
                      type="text"
                      inputMode="numeric"
                      pattern="[0-9]*"
                      value={group.maxTokens != null ? String(group.maxTokens) : ''}
                      onChange={(e) => {
                        const value = e.target.value;
                        if (value !== '' && !/^\d+$/.test(value)) {
                          return;
                        }
                        setAgentGroups(prev => prev.map((g, idx) =>
                          idx === groupIndex ? { ...g, maxTokens: value === '' ? undefined : Number(value) } : g
                        ));
                      }}
                      placeholder="4096"
                      className="w-full px-3 py-2 bg-white border border-gray-300 rounded-lg text-gray-900 placeholder-gray-400 focus:outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-500 shadow-sm"
                    />
                  </div>
                </div>

                {/* Save Button with Success Message */}
                <div className="mt-6 flex items-center justify-end gap-3">
                  {successMessage[groupKey] && (
                    <div className="flex items-center gap-2 text-green-600">
                      <CheckCircle className="w-5 h-5" />
                      <span className="text-sm">{t('systemConfig.savedSuccessfully')}</span>
                    </div>
                  )}
                  <button
                    onClick={() => {
                      handleSave(groupIndex, group.providerId, group.model);
                    }}
                    disabled={saving[groupKey]}
                    className="px-4 py-2 bg-blue-600 hover:bg-blue-700 disabled:bg-gray-400 disabled:cursor-not-allowed text-white rounded-lg font-medium transition-colors duration-200 flex items-center gap-2 cursor-pointer shadow-sm"
                  >
                    {saving[groupKey] ? (
                      <>
                        <Loader2 className="w-4 h-4 animate-spin" />
                        {t('systemConfig.saving')}
                      </>
                    ) : (
                      t('systemConfig.saveConfiguration')
                    )}
                  </button>
                </div>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}
