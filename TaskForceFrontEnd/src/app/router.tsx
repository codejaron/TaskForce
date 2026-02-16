import { createBrowserRouter, createHashRouter } from 'react-router-dom';
import { DashboardPage } from '../pages/Dashboard/ui/DashboardPage';
import { AgentWorkshopPage } from '../pages/AgentWorkshop/ui/AgentWorkshopPage';
import { McpMarketplacePage } from '../pages/McpMarketplace/ui/McpMarketplacePage';
import { TeamStudioPage } from '../pages/TeamStudio/ui/TeamStudioPage';
import { SingleChatPage } from '../pages/SingleChat/ui/SingleChatPage';
import { LLMProvidersPage } from '../pages/LLMProviders/ui/LLMProvidersPage';
import { SystemConfigPage } from '../pages/SystemConfig/ui/SystemConfigPage';
import { SkillsPage } from '../pages/Skills/ui/SkillsPage';
import { Layout } from './Layout';

const routes = [
  {
    path: '/',
    element: <Layout />,
    children: [
      {
        index: true,
        element: <DashboardPage />,
      },
      {
        path: 'agents',
        element: <AgentWorkshopPage />,
      },
      {
        path: 'mcp',
        element: <McpMarketplacePage />,
      },
      {
        path: 'single-chat',
        element: <SingleChatPage />,
      },
      {
        path: 'team-studio',
        element: <TeamStudioPage />,
      },
      {
        path: 'providers',
        element: <LLMProvidersPage />,
      },
      {
        path: 'system-config',
        element: <SystemConfigPage />,
      },
      {
        path: 'skills',
        element: <SkillsPage />,
      },
    ],
  },
];

const isFileProtocol = typeof window !== 'undefined' && window.location.protocol === 'file:';

export const router = isFileProtocol
  ? createHashRouter(routes)
  : createBrowserRouter(routes);
