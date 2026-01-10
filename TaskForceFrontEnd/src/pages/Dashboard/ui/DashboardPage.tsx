import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  Server,
  Bot,
  Database,
  Users,
  ArrowRight,
  Activity,
  Zap,
  Calendar
} from 'lucide-react';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { api } from '../../../shared/api';
import type {
  ProviderCostDTO,
  ModelUsageDTO,
  DailyCostDTO,
  AgentUsageDTO
} from '../../../shared/api/types';

interface Stats {
  providers: number;
  agents: number;
  mcpServers: number;
  mcpTools: number;
  sessions: number;
}

export const DashboardPage: React.FC = () => {
  const { t } = useTranslation();
  const [stats, setStats] = useState<Stats>({
    providers: 0,
    agents: 0,
    mcpServers: 0,
    mcpTools: 0,
    sessions: 0
  });
  const [loading, setLoading] = useState(true);

  const [tokenStats, setTokenStats] = useState({
    providerCost: [] as ProviderCostDTO[],
    topModels: [] as ModelUsageDTO[],
    dailyCost: [] as DailyCostDTO[],
    topAgents: [] as AgentUsageDTO[],
    totalCost: 0,
    totalTokens: 0,
    totalCalls: 0
  });

  const [dateRange, setDateRange] = useState({
    startDate: new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString().split('T')[0],
    endDate: new Date().toISOString().split('T')[0]
  });

  // Helper function to fill missing dates in daily cost data
  const fillMissingDates = (data: DailyCostDTO[], startDate: string, endDate: string): DailyCostDTO[] => {
    const start = new Date(startDate);
    const end = new Date(endDate);
    const filledData: DailyCostDTO[] = [];

    // Create a map of existing data
    const dataMap = new Map<string, DailyCostDTO>();
    data.forEach(item => {
      dataMap.set(item.date, item);
    });

    // Fill all dates in range
    const currentDate = new Date(start);
    while (currentDate <= end) {
      const dateStr = currentDate.toISOString().split('T')[0];
      if (dataMap.has(dateStr)) {
        filledData.push(dataMap.get(dateStr)!);
      } else {
        filledData.push({
          date: dateStr,
          totalCost: 0,
          totalTokens: 0,
          callCount: 0
        });
      }
      currentDate.setDate(currentDate.getDate() + 1);
    }

    return filledData;
  };

  useEffect(() => {
    const fetchStats = async () => {
      try {
        const [providers, agents, servers, tools, sessions] = await Promise.all([
          api.llmProviders.list().catch(() => []),
          api.agents.list().catch(() => []),
          api.mcp.listServers().catch(() => []),
          api.mcp.listTools().catch(() => []),
          api.sessions.list().catch(() => [])
        ]);

        setStats({
          providers: providers.length,
          agents: agents.length,
          mcpServers: servers.length,
          mcpTools: tools.length,
          sessions: sessions.length
        });

        // Load Token statistics data
        const [providerCost, topModels, dailyCost, topAgents] = await Promise.all([
          api.tokenUsage.getProviderCost(dateRange.startDate, dateRange.endDate).catch(() => []),
          api.tokenUsage.getTopModels(dateRange.startDate, dateRange.endDate, 5).catch(() => []),
          api.tokenUsage.getDailyCostTrend(dateRange.startDate, dateRange.endDate).catch(() => []),
          api.tokenUsage.getTopAgents(dateRange.startDate, dateRange.endDate, 5).catch(() => [])
        ]);

        // Filter out zero-token items
        const filteredModels = topModels.filter(m => m.totalTokens > 0);
        const filteredAgents = topAgents.filter(a => a.totalTokens > 0);

        // Fill missing dates in daily trend
        const filledDailyCost = fillMissingDates(dailyCost, dateRange.startDate, dateRange.endDate);

        const totalCost = providerCost.reduce((sum, p) => sum + p.totalCost, 0);
        const totalTokens = providerCost.reduce((sum, p) => sum + p.totalTokens, 0);
        const totalCalls = providerCost.reduce((sum, p) => sum + p.callCount, 0);

        setTokenStats({
          providerCost,
          topModels: filteredModels,
          dailyCost: filledDailyCost,
          topAgents: filteredAgents,
          totalCost,
          totalTokens,
          totalCalls
        });

      } catch (error) {
        console.error('Failed to fetch stats:', error);
      } finally {
        setLoading(false);
      }
    };

    fetchStats();
  }, [dateRange]);

  const statCards = [
    {
      title: t('dashboard.llmProviders'),
      value: stats.providers,
      icon: Server,
      color: 'bg-blue-600',
      link: '/providers',
      description: t('dashboard.llmProvidersDesc')
    },
    {
      title: t('dashboard.agents'),
      value: stats.agents,
      icon: Bot,
      color: 'bg-purple-600',
      link: '/agents',
      description: t('dashboard.agentsDesc')
    },
    {
      title: t('dashboard.mcpTools'),
      value: stats.mcpTools,
      icon: Database,
      color: 'bg-orange-600',
      link: '/mcp',
      description: t('dashboard.mcpToolsDesc', { count: stats.mcpServers })
    },
    {
      title: t('dashboard.a2aSessions'),
      value: stats.sessions,
      icon: Users,
      color: 'bg-green-600',
      link: '/a2a',
      description: t('dashboard.a2aSessionsDesc')
    }
  ];

  return (
    <div className="bg-slate-50 p-8">
      <div className="max-w-7xl mx-auto pb-12">
        {/* Header */}
        <div className="mb-12">
          <div className="flex items-center gap-3 mb-2">
            <div className="w-10 h-10 rounded-xl bg-purple-600 flex items-center justify-center shadow-sm">
              <Zap size={20} className="text-white" />
            </div>
            <h1 className="text-3xl font-bold font-heading text-gray-900">{t('dashboard.title')}</h1>
          </div>
          <p className="text-gray-600 text-lg">
            {t('dashboard.welcome')}. {t('dashboard.description')}
          </p>
        </div>

        {/* Stats Grid */}
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6 mb-12">
          {statCards.map((card) => (
            <Link
              key={card.title}
              to={card.link}
              className="group relative overflow-hidden bg-white border border-gray-200 rounded-2xl p-6 hover:border-gray-300 transition-all duration-200 hover:shadow-md hover:-translate-y-0.5 cursor-pointer"
            >
              {/* Icon */}
              <div className={`w-12 h-12 rounded-xl ${card.color} flex items-center justify-center mb-4 shadow-sm`}>
                <card.icon size={24} className="text-white" />
              </div>

              {/* Content */}
              <div className="relative">
                <div className="flex items-baseline gap-2 mb-1">
                  <span className="text-4xl font-bold font-heading text-gray-900">
                    {loading ? '-' : card.value}
                  </span>
                </div>
                <h3 className="text-lg font-semibold text-gray-900 mb-1">{card.title}</h3>
                <p className="text-sm text-gray-600">{card.description}</p>
              </div>

              {/* Arrow */}
              <ArrowRight
                size={20}
                className="absolute top-6 right-6 text-gray-400 group-hover:text-gray-600 group-hover:translate-x-1 transition-all duration-200"
              />
            </Link>
          ))}
        </div>

        {/* Token Usage Statistics */}
        <div className="space-y-6">
          {/* Header with Date Range Picker */}
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <Activity size={24} className="text-blue-600" />
              <h2 className="text-xl font-bold font-heading text-gray-900">
                {t('dashboard.tokenStatistics')}
              </h2>
            </div>
            <div className="flex items-center gap-3 bg-white border border-gray-200 rounded-xl px-4 py-2">
              <Calendar size={18} className="text-gray-600" />
              <input
                type="date"
                value={dateRange.startDate}
                onChange={(e) => setDateRange({ ...dateRange, startDate: e.target.value })}
                className="border-0 focus:ring-0 text-sm"
              />
              <span className="text-gray-500">to</span>
              <input
                type="date"
                value={dateRange.endDate}
                onChange={(e) => setDateRange({ ...dateRange, endDate: e.target.value })}
                className="border-0 focus:ring-0 text-sm"
              />
            </div>
          </div>

          {/* Overview Cards */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            <div className="bg-white border border-gray-200 rounded-2xl p-6 shadow-sm">
              <div className="flex items-center gap-3 mb-2">
                <Activity size={20} className="text-blue-600" />
                <h3 className="text-sm font-semibold text-gray-600">{t('dashboard.totalTokens')}</h3>
              </div>
              <p className="text-3xl font-bold text-gray-900">
                {(tokenStats.totalTokens / 1000).toFixed(1)}K
              </p>
            </div>
            <div className="bg-white border border-gray-200 rounded-2xl p-6 shadow-sm">
              <div className="flex items-center gap-3 mb-2">
                <Zap size={20} className="text-purple-600" />
                <h3 className="text-sm font-semibold text-gray-600">{t('dashboard.apiCalls')}</h3>
              </div>
              <p className="text-3xl font-bold text-gray-900">{tokenStats.totalCalls}</p>
            </div>
          </div>

          {/* Charts Grid */}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            {/* Daily Token Trend */}
            <div className="bg-white border border-gray-200 rounded-2xl p-6 shadow-sm">
              <h3 className="text-lg font-bold text-gray-900 mb-4">
                {t('dashboard.dailyTokenTrend')}
              </h3>
              <ResponsiveContainer width="100%" height={200}>
                <LineChart data={tokenStats.dailyCost}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis
                    dataKey="date"
                    tickFormatter={(date) => new Date(date).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}
                  />
                  <YAxis />
                  <Tooltip
                    formatter={(value: number | undefined) => value !== undefined ? `${value.toLocaleString()} tokens` : '0 tokens'}
                    labelFormatter={(date) => new Date(date).toLocaleDateString()}
                  />
                  <Line type="monotone" dataKey="totalTokens" stroke="#10b981" strokeWidth={2} />
                </LineChart>
              </ResponsiveContainer>
            </div>

            {/* Provider Token Distribution */}
            <div className="bg-white border border-gray-200 rounded-2xl p-6 shadow-sm">
              <h3 className="text-lg font-bold text-gray-900 mb-4">
                {t('dashboard.providerTokens')}
              </h3>
              {tokenStats.providerCost.length > 0 ? (
                <div className="space-y-3">
                  {tokenStats.providerCost.map((provider) => (
                    <div key={provider.providerId}>
                      <div className="flex items-center justify-between mb-1">
                        <span className="font-medium text-gray-900">{provider.providerName}</span>
                        <span className="text-sm text-gray-600">{provider.totalTokens.toLocaleString()} tokens</span>
                      </div>
                      <div className="w-full bg-gray-200 rounded-full h-2">
                        <div
                          className="bg-green-600 h-2 rounded-full transition-all"
                          style={{ width: `${tokenStats.totalTokens > 0 ? (provider.totalTokens / tokenStats.totalTokens) * 100 : 0}%` }}
                        />
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <p className="text-gray-500 text-center py-8">{t('common.noData')}</p>
              )}
            </div>

            {/* Top Models */}
            <div className="bg-white border border-gray-200 rounded-2xl p-6 shadow-sm">
              <h3 className="text-lg font-bold text-gray-900 mb-4">
                {t('dashboard.topModels')}
              </h3>
              {tokenStats.topModels.length > 0 ? (
                <div className="space-y-3">
                  {tokenStats.topModels.map((model, idx) => (
                    <div key={model.modelName} className="flex items-center justify-between border-b border-gray-100 pb-2">
                      <div>
                        <p className="font-medium text-gray-900">{idx + 1}. {model.modelName}</p>
                        <p className="text-xs text-gray-500">
                          {model.callCount} calls
                        </p>
                      </div>
                      <span className="text-sm font-semibold text-gray-900">{model.totalTokens.toLocaleString()} tokens</span>
                    </div>
                  ))}
                </div>
              ) : (
                <p className="text-gray-500 text-center py-8">{t('common.noData')}</p>
              )}
            </div>

            {/* Top Agents */}
            <div className="bg-white border border-gray-200 rounded-2xl p-6 shadow-sm">
              <h3 className="text-lg font-bold text-gray-900 mb-4">
                {t('dashboard.topAgents')}
              </h3>
              {tokenStats.topAgents.length > 0 ? (
                <div className="space-y-3">
                  {tokenStats.topAgents.map((agent, idx) => (
                    <div key={agent.agentId} className="flex items-center justify-between border-b border-gray-100 pb-2">
                      <div>
                        <p className="font-medium text-gray-900">{idx + 1}. {agent.agentName}</p>
                        <p className="text-xs text-gray-500">
                          {agent.callCount} calls
                        </p>
                      </div>
                      <span className="text-sm font-semibold text-gray-900">{agent.totalTokens.toLocaleString()} tokens</span>
                    </div>
                  ))}
                </div>
              ) : (
                <p className="text-gray-500 text-center py-8">{t('common.noData')}</p>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
