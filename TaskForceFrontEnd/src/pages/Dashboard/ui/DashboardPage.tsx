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
  TrendingUp
} from 'lucide-react';
import { api } from '../../../shared/api';

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
      } catch (error) {
        console.error('Failed to fetch stats:', error);
      } finally {
        setLoading(false);
      }
    };

    fetchStats();
  }, []);

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

  const quickActions = [
    {
      title: t('dashboard.configureProvider'),
      description: t('dashboard.configureProviderDesc'),
      icon: Server,
      link: '/providers',
      color: 'bg-blue-50 text-blue-700 border-blue-200'
    },
    {
      title: t('dashboard.createAgent'),
      description: t('dashboard.createAgentDesc'),
      icon: Bot,
      link: '/agents',
      color: 'bg-purple-50 text-purple-700 border-purple-200'
    },
    {
      title: t('dashboard.connectMcpServer'),
      description: t('dashboard.connectMcpServerDesc'),
      icon: Database,
      link: '/mcp',
      color: 'bg-orange-50 text-orange-700 border-orange-200'
    },
    {
      title: t('dashboard.startA2aSession'),
      description: t('dashboard.startA2aSessionDesc'),
      icon: Users,
      link: '/a2a',
      color: 'bg-green-50 text-green-700 border-green-200'
    }
  ];

  return (
    <div className="min-h-full bg-slate-50 p-8">
      <div className="max-w-7xl mx-auto">
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

        {/* Quick Actions */}
        <div className="mb-12">
          <div className="flex items-center gap-3 mb-6">
            <Activity size={24} className="text-purple-600" />
            <h2 className="text-xl font-bold font-heading text-gray-900">{t('dashboard.quickActions')}</h2>
          </div>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {quickActions.map((action) => (
              <Link
                key={action.title}
                to={action.link}
                className={`flex items-center gap-4 p-5 rounded-xl border ${action.color} bg-white hover:shadow-sm transition-all duration-200 group cursor-pointer`}
              >
                <div className="w-12 h-12 rounded-xl bg-white border border-gray-200 flex items-center justify-center flex-shrink-0">
                  <action.icon size={24} className="text-gray-600" />
                </div>
                <div className="flex-1 min-w-0">
                  <h3 className="font-semibold text-gray-900 mb-1">{action.title}</h3>
                  <p className="text-sm text-gray-600 truncate">{action.description}</p>
                </div>
                <ArrowRight
                  size={20}
                  className="text-gray-400 group-hover:text-gray-600 group-hover:translate-x-1 transition-all duration-200 flex-shrink-0"
                />
              </Link>
            ))}
          </div>
        </div>

        {/* System Status */}
        <div className="bg-white border border-gray-200 rounded-2xl p-6 shadow-sm">
          <div className="flex items-center gap-3 mb-6">
            <TrendingUp size={24} className="text-green-600" />
            <h2 className="text-xl font-bold font-heading text-gray-900">{t('dashboard.systemStatus')}</h2>
          </div>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
            <div className="flex items-center gap-4">
              <div className="w-3 h-3 rounded-full bg-green-500 animate-pulse" />
              <div>
                <p className="text-gray-900 font-medium">{t('dashboard.backendApi')}</p>
                <p className="text-sm text-gray-600">{t('dashboard.connected')}</p>
              </div>
            </div>
            <div className="flex items-center gap-4">
              <div className={`w-3 h-3 rounded-full ${stats.providers > 0 ? 'bg-green-500' : 'bg-yellow-500'}`} />
              <div>
                <p className="text-gray-900 font-medium">{t('dashboard.llmProviders')}</p>
                <p className="text-sm text-gray-600">
                  {stats.providers > 0 ? `${stats.providers} ${t('dashboard.configured')}` : t('common.noData')}
                </p>
              </div>
            </div>
            <div className="flex items-center gap-4">
              <div className={`w-3 h-3 rounded-full ${stats.mcpServers > 0 ? 'bg-green-500' : 'bg-gray-400'}`} />
              <div>
                <p className="text-gray-900 font-medium">MCP Servers</p>
                <p className="text-sm text-gray-600">
                  {stats.mcpServers > 0 ? `${stats.mcpServers} ${t('dashboard.connected')}` : t('mcp.noServers')}
                </p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
