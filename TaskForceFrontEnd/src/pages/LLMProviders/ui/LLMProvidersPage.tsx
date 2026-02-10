import { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Plus,
  Server,
  Trash2,
  Edit,
  Eye,
  EyeOff,
  Key,
  Download,
  ChevronDown,
  ChevronUp,
  Loader2,
  Zap
} from 'lucide-react';
import { api } from '../../../shared/api';
import type { LLMProvider, ChannelModel, LLMProviderRequest } from '../../../shared/api/types';

type ProviderType = LLMProvider['providerType'];

interface FormState {
  channelName: string;
  providerType: ProviderType;
  apiBaseUrl: string;
  apiKey: string;
  enabled: boolean;
}

const providerIcons: Record<string, { color: string; bg: string }> = {
  OPENAI: { color: 'text-green-700', bg: 'bg-green-600' },
  AZURE_OPENAI: { color: 'text-blue-700', bg: 'bg-blue-600' },
  DEEPSEEK: { color: 'text-purple-700', bg: 'bg-purple-600' },
  OLLAMA: { color: 'text-orange-700', bg: 'bg-orange-600' },
  CUSTOM: { color: 'text-gray-700', bg: 'bg-gray-600' }
};

export function LLMProvidersPage() {
  const { t } = useTranslation();
  const [providers, setProviders] = useState<LLMProvider[]>([]);
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [editingProvider, setEditingProvider] = useState<LLMProvider | null>(null);
  const [showApiKey, setShowApiKey] = useState<Record<number, boolean>>({});
  const [decryptedApiKeys, setDecryptedApiKeys] = useState<Record<number, string>>({});
  const [expandedProvider, setExpandedProvider] = useState<number | null>(null);
  const [providerModels, setProviderModels] = useState<Record<number, ChannelModel[]>>({});
  const [loadingModels, setLoadingModels] = useState<number | null>(null);
  const [formData, setFormData] = useState<FormState>({
    channelName: '',
    providerType: 'OPENAI',
    apiBaseUrl: '',
    apiKey: '',
    enabled: true,
  });
  const [models, setModels] = useState<ChannelModel[]>([]);
  const [isSaving, setIsSaving] = useState(false);

  const loadProviders = async () => {
    try {
      const data = await api.llmProviders.list();
      setProviders(data);
    } catch (error) {
      console.error('Failed to load providers:', error);
    }
  };

  useEffect(() => {
    loadProviders();
  }, []);

  const loadProviderModels = async (providerId: number) => {
    if (providerModels[providerId]) {
      return; // Already loaded
    }
    setLoadingModels(providerId);
    try {
      const models = await api.llmProviders.listModels(providerId);
      setProviderModels(prev => ({ ...prev, [providerId]: models || [] }));
    } catch (error) {
      console.error('Failed to load models:', error);
    } finally {
      setLoadingModels(null);
    }
  };

  const toggleExpand = (providerId: number) => {
    if (expandedProvider === providerId) {
      setExpandedProvider(null);
    } else {
      setExpandedProvider(providerId);
      loadProviderModels(providerId);
    }
  };

  const toggleShowApiKey = async (providerId: number) => {
    const isCurrentlyShown = showApiKey[providerId];

    if (!isCurrentlyShown && !decryptedApiKeys[providerId]) {
      // 需要获取解密的 API Key
      try {
        const result = await api.llmProviders.getApiKey(providerId);
        setDecryptedApiKeys(prev => ({ ...prev, [providerId]: result.apiKey }));
      } catch (error) {
        console.error('Failed to get API key:', error);
        return;
      }
    }

    setShowApiKey(prev => ({ ...prev, [providerId]: !prev[providerId] }));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsSaving(true);
    try {
      const suppliedBase = formData.apiBaseUrl?.trim() || undefined;
      const payload: LLMProviderRequest = {
        name: formData.channelName,
        type: formData.providerType as string,
        baseUrl: suppliedBase,
        apiKey: formData.apiKey || undefined,
        config: JSON.stringify({
          enabled: formData.enabled
        }),
      };

      if (models.length > 0) {
        payload.models = models.map(m => ({
          modelValue: m.modelValue,
          displayName: m.displayName
        }));
      }

      if (editingProvider) {
        await api.llmProviders.update(editingProvider.id, payload);
      } else {
        await api.llmProviders.create(payload);
      }
      setIsModalOpen(false);
      resetForm();
      loadProviders();
    } catch (error) {
      console.error('Failed to save provider:', error);
    } finally {
      setIsSaving(false);
    }
  };

  const handleDelete = async (id: number) => {
    if (!confirm('Are you sure you want to delete this LLM provider?')) return;
    try {
      await api.llmProviders.delete(id);
      loadProviders();
    } catch (error) {
      console.error('Failed to delete provider:', error);
    }
  };

  const handleEdit = async (provider: LLMProvider) => {
    try {
      const full = await api.llmProviders.get(provider.id) as LLMProvider;
      const baseUrl = full.baseUrl ?? '';

      setEditingProvider(full);
      setFormData({
        channelName: full.name ?? full.channelName ?? '',
        providerType: (full.type ?? full.providerType) || 'OPENAI',
        apiBaseUrl: baseUrl || '',
        apiKey: '',
        enabled: full.enabled ?? true,
      } as FormState);

      try {
        const ms = await api.llmProviders.listModels(provider.id);
        setModels(ms || []);
      } catch {
        setModels([]);
      }

      setIsModalOpen(true);
    } catch (err) {
      console.error('Failed to fetch provider details', err);
      alert('Failed to load provider details from server.');
    }
  };

  const resetForm = () => {
    setEditingProvider(null);
    setFormData({
      channelName: '',
      providerType: 'OPENAI',
      apiBaseUrl: '',
      apiKey: '',
      enabled: true,
    } as FormState);
    setModels([]);
  };

  const addModelRow = () => {
    setModels(prev => [...prev, { modelValue: '', displayName: '' }]);
  };

  const updateModelRow = (index: number, field: keyof ChannelModel, value: string) => {
    setModels(prev => prev.map((m, i) => i === index ? { ...m, [field]: value } : m));
  };

  const removeModelRow = (index: number) => {
    setModels(prev => prev.filter((_, i) => i !== index));
  };

  const fetchRemoteModels = async () => {
    const base = formData.apiBaseUrl?.trim();
    if (!base) {
      alert('Please enter the API Base URL first.');
      return;
    }

    try {
      if (editingProvider) {
        const ms = await api.remoteModels.fetchForProvider(editingProvider.id);
        setModels(ms || []);
      } else {
        const ms = await api.remoteModels.fetch(base, formData.apiKey);
        setModels(ms || []);
      }
    } catch (err) {
      console.error('Failed to fetch remote models:', err);
      alert('Failed to fetch models. Check base URL and API Key.');
    }
  };

  const getProviderStyle = (type: string) => {
    return providerIcons[type] || providerIcons.CUSTOM;
  };

  return (
    <div className="min-h-full bg-slate-50 p-8">
      <div className="max-w-7xl mx-auto">
        {/* Header */}
        <div className="flex justify-between items-start mb-8">
          <div>
            <div className="flex items-center gap-3 mb-2">
              <div className="w-10 h-10 rounded-xl bg-blue-600 flex items-center justify-center shadow-sm">
                <Server size={20} className="text-white" />
              </div>
              <h1 className="text-3xl font-bold font-heading text-gray-900">{t('providers.title')}</h1>
            </div>
            <p className="text-gray-600">{t('providers.configureChannels')}</p>
          </div>
          <button
            onClick={() => { resetForm(); setIsModalOpen(true); }}
            className="flex items-center gap-2 bg-blue-600 hover:bg-blue-700 text-white px-6 py-3 rounded-xl font-medium transition-colors duration-200 shadow-sm cursor-pointer"
          >
            <Plus size={20} />
            {t('providers.addProvider')}
          </button>
        </div>

        {/* Provider Grid */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {providers.map((provider) => {
            const style = getProviderStyle(provider.type || provider.providerType || 'CUSTOM');
            const isExpanded = expandedProvider === provider.id;
            const currentModels = providerModels[provider.id] || [];

            return (
              <div
                key={provider.id}
                className="bg-white rounded-2xl border border-gray-200 overflow-hidden hover:border-gray-300 hover:shadow-md transition-all duration-200 group cursor-pointer"
              >
                {/* Header */}
                <div className="p-6">
                  <div className="flex justify-between items-start mb-4">
                    <div className="flex items-center gap-4">
                      <div className={`w-14 h-14 rounded-xl ${style.bg} flex items-center justify-center shadow-sm`}>
                        <Server size={28} className="text-white" />
                      </div>
                      <div>
                        <h3 className="font-bold text-xl font-heading text-gray-900">{provider.name || provider.channelName}</h3>
                        <div className="flex items-center gap-2 mt-1">
                          <span className={`text-xs px-2 py-0.5 rounded-full ${style.color} bg-gray-100`}>
                            {provider.type || provider.providerType}
                          </span>
                        </div>
                      </div>
                    </div>
                  </div>

                  {/* Info */}
                  <div className="space-y-2 text-sm">
                    {provider.baseUrl && (
                      <div className="flex items-center gap-2">
                        <span className="text-gray-600">Base URL:</span>
                        <span className="text-blue-600 truncate font-mono text-xs">{provider.baseUrl}</span>
                      </div>
                    )}
                    <div className="flex items-center gap-2">
                      <Key size={14} className="text-gray-600" />
                      <span className="text-gray-600">API Key:</span>
                      <span className="text-gray-900 font-mono text-xs">
                        {showApiKey[provider.id]
                          ? (decryptedApiKeys[provider.id] || provider.apiKey || '••••••••••••')
                          : (provider.apiKey || '••••••••••••')}
                      </span>
                      <button
                        onClick={() => toggleShowApiKey(provider.id)}
                        className="text-gray-600 hover:text-gray-900 transition-colors duration-200 cursor-pointer"
                      >
                        {showApiKey[provider.id] ? <EyeOff size={14} /> : <Eye size={14} />}
                      </button>
                    </div>
                  </div>

                  {/* Expand Models Button */}
                  <button
                    onClick={() => toggleExpand(provider.id)}
                    className="w-full mt-4 flex items-center justify-center gap-2 py-2 text-sm text-gray-600 hover:text-gray-900 transition-colors duration-200 cursor-pointer"
                  >
                    {loadingModels === provider.id ? (
                      <Loader2 size={16} className="animate-spin" />
                    ) : isExpanded ? (
                      <ChevronUp size={16} />
                    ) : (
                      <ChevronDown size={16} />
                    )}
                    <span>{isExpanded ? t('providers.hideModels') : t('providers.showModels')}</span>
                  </button>
                </div>

                {/* Models List (Expandable) */}
                {isExpanded && (
                  <div className="border-t border-gray-200 p-4 bg-slate-50">
                    {currentModels.length === 0 ? (
                      <p className="text-sm text-gray-500 text-center py-4">No models configured</p>
                    ) : (
                      <div className="space-y-2 max-h-64 overflow-y-auto">
                        {currentModels.map((model, idx) => (
                          <div key={idx} className="flex items-center gap-3 px-3 py-2 bg-white border border-gray-200 rounded-lg">
                            <Zap size={14} className="text-yellow-600" />
                            <span className="text-sm text-gray-900 font-mono">{model.modelValue}</span>
                            <span className="text-xs text-gray-600">({model.displayName})</span>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                )}

                {/* Actions */}
                <div className="border-t border-gray-200 p-4 flex gap-2">
                  <button
                    onClick={() => handleEdit(provider)}
                    className="flex-1 flex items-center justify-center gap-2 bg-white hover:bg-gray-50 text-gray-700 px-4 py-2.5 rounded-xl transition-colors duration-200 border border-gray-200 cursor-pointer"
                  >
                    <Edit size={16} />
                    {t('providers.edit')}
                  </button>
                  <button
                    onClick={() => handleDelete(provider.id)}
                    className="flex items-center justify-center gap-2 bg-red-50 hover:bg-red-100 text-red-600 px-4 py-2.5 rounded-xl transition-colors duration-200 border border-red-200 cursor-pointer"
                  >
                    <Trash2 size={16} />
                  </button>
                </div>
              </div>
            );
          })}
        </div>

        {/* Empty State */}
        {providers.length === 0 && (
          <div className="text-center py-20">
            <div className="w-20 h-20 mx-auto mb-6 rounded-2xl bg-blue-50 flex items-center justify-center">
              <Server size={40} className="text-blue-600" />
            </div>
            <h3 className="text-xl font-bold font-heading text-gray-900 mb-2">{t('providers.noProviders')}</h3>
            <p className="text-gray-600 mb-6">Get started by adding your first AI model provider</p>
            <button
              onClick={() => { resetForm(); setIsModalOpen(true); }}
              className="inline-flex items-center gap-2 bg-blue-600 hover:bg-blue-700 text-white px-6 py-3 rounded-xl font-medium transition-colors duration-200 cursor-pointer shadow-sm"
            >
              <Plus size={20} />
              Add Provider
            </button>
          </div>
        )}
      </div>

      {/* Modal */}
      {isModalOpen && (
        <div className="fixed inset-0 bg-black/50 backdrop-blur-sm flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-2xl w-full max-w-2xl border border-gray-200 shadow-2xl max-h-[90vh] overflow-y-auto">
            <div className="p-6 border-b border-gray-200 sticky top-0 bg-white z-10">
              <h2 className="text-2xl font-bold font-heading text-gray-900">
                {editingProvider ? t('providers.edit') + ' Provider' : t('providers.addNewProvider')}
              </h2>
            </div>

            <form onSubmit={handleSubmit} className="p-6 space-y-5">
              {/* Channel Name */}
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">{t('providers.channelName')} *</label>
                <input
                  type="text"
                  value={formData.channelName}
                  onChange={(e) => setFormData({ ...formData, channelName: e.target.value })}
                  className="w-full bg-white border border-gray-300 rounded-xl px-4 py-3 text-gray-900 focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-colors duration-200 shadow-sm"
                  placeholder={t('providers.channelNamePlaceholder')}
                  required
                />
              </div>

              {/* Provider Type */}
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">{t('providers.providerType')} *</label>
                <select
                  value={formData.providerType}
                  onChange={(e) => setFormData({ ...formData, providerType: e.target.value as ProviderType })}
                  className="w-full bg-white border border-gray-300 rounded-xl px-4 py-3 text-gray-900 focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none cursor-pointer shadow-sm"
                >
                  <option value="OPENAI">OpenAI</option>
                  <option value="AZURE_OPENAI">Azure OpenAI</option>
                  <option value="OLLAMA">Ollama</option>
                  <option value="CUSTOM">Custom</option>
                </select>
              </div>

              {/* API Base URL */}
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">{t('providers.baseUrlLabel')} *</label>
                <input
                  type="url"
                  value={formData.apiBaseUrl}
                  onChange={(e) => setFormData({ ...formData, apiBaseUrl: e.target.value })}
                  className="w-full bg-white border border-gray-300 rounded-xl px-4 py-3 text-gray-900 focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none font-mono text-sm shadow-sm"
                  placeholder="https://api.openai.com/v1"
                  required
                />
              </div>

              {/* API Key */}
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">{t('providers.apiKeyLabel')} *</label>
                <input
                  type="password"
                  value={formData.apiKey}
                  onChange={(e) => setFormData({ ...formData, apiKey: e.target.value })}
                  className="w-full bg-white border border-gray-300 rounded-xl px-4 py-3 text-gray-900 focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none font-mono shadow-sm"
                  placeholder="sk-..."
                  required={!editingProvider}
                />
                {editingProvider && (
                  <p className="text-xs text-gray-500 mt-1">Leave empty to keep existing key</p>
                )}
              </div>

              {/* Models Section */}
              <div>
                <div className="flex items-center justify-between mb-3">
                  <label className="text-sm font-medium text-gray-700">{t('providers.models')}</label>
                  <button
                    type="button"
                    onClick={fetchRemoteModels}
                    className="text-sm text-blue-600 hover:text-blue-700 flex items-center gap-1 cursor-pointer transition-colors duration-200"
                  >
                    <Download size={14} />
                    {t('providers.fetchFromServer')}
                  </button>
                </div>
                <div className="space-y-2 max-h-80 overflow-y-auto">
                  {models.map((model, index) => (
                    <div key={index} className="flex gap-2">
                      <input
                        type="text"
                        value={model.modelValue}
                        onChange={(e) => updateModelRow(index, 'modelValue', e.target.value)}
                        className="flex-1 bg-white border border-gray-300 rounded-xl px-4 py-2.5 text-gray-900 focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none text-sm font-mono shadow-sm"
                        placeholder="Model ID (e.g., gpt-4o)"
                      />
                      <input
                        type="text"
                        value={model.displayName}
                        onChange={(e) => updateModelRow(index, 'displayName', e.target.value)}
                        className="flex-1 bg-white border border-gray-300 rounded-xl px-4 py-2.5 text-gray-900 focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none text-sm shadow-sm"
                        placeholder="Display Name"
                      />
                      <button
                        type="button"
                        onClick={() => removeModelRow(index)}
                        className="p-2.5 bg-red-50 hover:bg-red-100 text-red-600 rounded-xl transition-colors duration-200 cursor-pointer"
                      >
                        <Trash2 size={16} />
                      </button>
                    </div>
                  ))}
                </div>
                <button
                  type="button"
                  onClick={addModelRow}
                  className="mt-3 flex items-center gap-2 text-sm text-gray-600 hover:text-gray-900 transition-colors duration-200 cursor-pointer"
                >
                  <Plus size={16} />
                  {t('providers.addModelManually')}
                </button>
              </div>

              {/* Actions */}
              <div className="flex justify-end gap-3 pt-4 border-t border-gray-200">
                <button
                  type="button"
                  onClick={() => { setIsModalOpen(false); resetForm(); }}
                  className="px-6 py-2.5 text-gray-700 hover:text-gray-900 hover:bg-gray-100 rounded-xl transition-colors duration-200 cursor-pointer"
                >
                  {t('common.cancel')}
                </button>
                <button
                  type="submit"
                  disabled={isSaving}
                  className="px-6 py-2.5 bg-blue-600 hover:bg-blue-700 text-white rounded-xl transition-colors duration-200 font-medium disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2 cursor-pointer shadow-sm"
                >
                  {isSaving && <Loader2 size={16} className="animate-spin" />}
                  {editingProvider ? t('common.save') : t('providers.create')}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
