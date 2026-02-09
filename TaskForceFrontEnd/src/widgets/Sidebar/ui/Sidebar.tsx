import React from 'react';
import { LayoutDashboard, Users, Database, Bot, Server, Zap, PanelLeftClose, Settings, Sparkles, MessageCircle } from 'lucide-react';
import { clsx } from 'clsx';
import { Link, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { LanguageSwitcher } from '../../../shared/components/LanguageSwitcher';

interface SidebarProps {
  isOpen: boolean;
  toggleSidebar: () => void;
}

export const Sidebar: React.FC<SidebarProps> = ({ isOpen, toggleSidebar }) => {
  const location = useLocation();
  const { t } = useTranslation();

  const isActive = (path: string) => location.pathname === path;

  const navItems = [
    {
      path: '/',
      icon: LayoutDashboard,
      label: t('sidebar.dashboard'),
      color: 'text-blue-600'
    },
    {
      path: '/providers',
      icon: Server,
      label: t('sidebar.llmProviders'),
      color: 'text-cyan-600'
    },
    {
      path: '/agents',
      icon: Bot,
      label: t('sidebar.agentWorkshop'),
      color: 'text-purple-600'
    },
    {
      path: '/mcp',
      icon: Database,
      label: t('sidebar.mcpMarketplace'),
      color: 'text-orange-600'
    },
    {
      path: '/skills',
      icon: Sparkles,
      label: t('sidebar.skills'),
      color: 'text-yellow-600'
    },
    {
      path: '/single-chat',
      icon: MessageCircle,
      label: t('sidebar.singleChat'),
      color: 'text-blue-600'
    },
    {
      path: '/a2a',
      icon: Users,
      label: t('sidebar.a2aStudio'),
      color: 'text-green-600'
    },
    {
      path: '/system-config',
      icon: Settings,
      label: t('sidebar.systemConfig'),
      color: 'text-pink-600'
    }
  ];

  return (
    <>
      {/* Mobile Overlay */}
      <div
        className={clsx(
          "fixed inset-0 bg-black/50 z-20 md:hidden transition-opacity",
          isOpen ? "opacity-100" : "opacity-0 pointer-events-none"
        )}
        onClick={toggleSidebar}
      />

      {/* Sidebar Container */}
      <div className={clsx(
        "fixed md:static inset-y-0 left-0 z-30 bg-white text-gray-900 flex flex-col transition-all duration-300 ease-in-out border-r border-gray-200",
        isOpen ? "w-[260px]" : "-translate-x-full md:translate-x-0 md:w-[70px]"
      )}>
        {/* Logo / Brand */}
        <div className="p-4 border-b border-gray-200">
          {isOpen ? (
            <div className="flex items-center justify-between">
              <Link to="/" className="flex items-center gap-3 group">
                <div className="w-10 h-10 rounded-xl bg-purple-600 flex items-center justify-center shadow-lg group-hover:shadow-purple-500/25 transition-shadow shrink-0">
                  <Zap size={20} className="text-white" />
                </div>
                <div>
                  <h1 className="font-bold text-lg text-gray-900">{t('common.appName')}</h1>
                  <p className="text-xs text-gray-500">{t('common.appSubtitle')}</p>
                </div>
              </Link>
              {/* 收起按钮 - 在侧边栏内部 */}
              <button
                onClick={toggleSidebar}
                className="hidden md:flex p-2 hover:bg-gray-100 rounded-lg transition-colors shrink-0"
                title="收起侧边栏"
              >
                <PanelLeftClose size={18} className="text-gray-600 hover:text-gray-900" />
              </button>
            </div>
          ) : (
            <div className="flex flex-col items-center gap-3">
              <Link to="/" className="group">
                <div className="w-10 h-10 rounded-xl bg-purple-600 flex items-center justify-center shadow-lg group-hover:shadow-purple-500/25 transition-shadow">
                  <Zap size={20} className="text-white" />
                </div>
              </Link>
              {/* 展开按钮 - 收起状态下显示在 Logo 下方 */}
              <button
                onClick={toggleSidebar}
                className="hidden md:flex p-2 hover:bg-gray-100 rounded-lg transition-colors"
                title="展开侧边栏"
              >
                <svg width="16" height="16" viewBox="0 0 16 16" fill="none" className="text-gray-600">
                  <path d="M6 4L10 8L6 12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                </svg>
              </button>
            </div>
          )}
        </div>

        {/* Navigation Links */}
        <div className="flex-1 overflow-y-auto px-3 py-4">
          <div className="space-y-1">
            {navItems.map((item) => (
              <Link
                key={item.path}
                to={item.path}
                className={clsx(
                  "flex items-center w-full px-4 py-3 rounded-xl transition-all text-sm font-medium",
                  isOpen ? "gap-3" : "justify-center",
                  isActive(item.path)
                    ? "bg-purple-50 text-purple-700 border-l-2 border-l-purple-600"
                    : "hover:bg-gray-100 text-gray-600 hover:text-gray-900"
                )}
                title={!isOpen ? item.label : undefined}
              >
                <item.icon
                  size={20}
                  className={clsx(
                    "transition-colors shrink-0",
                    isActive(item.path) ? item.color : "text-gray-500"
                  )}
                />
                {isOpen && (
                  <>
                    <span>{item.label}</span>
                    {isActive(item.path) && (
                      <div className="ml-auto w-2 h-2 rounded-full bg-purple-600" />
                    )}
                  </>
                )}
              </Link>
            ))}
          </div>
        </div>

        {/* Language Switcher */}
        {isOpen && (
          <div className="p-3 border-t border-gray-200">
            <LanguageSwitcher />
          </div>
        )}
      </div>
    </>
  );
};
