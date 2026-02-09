import { createBrowserRouter } from 'react-router-dom';
import { DashboardPage } from '../pages/Dashboard/ui/DashboardPage';
import { AgentWorkshopPage } from '../pages/AgentWorkshop/ui/AgentWorkshopPage';
import { McpMarketplacePage } from '../pages/McpMarketplace/ui/McpMarketplacePage';
import { A2AStudioPage } from '../pages/A2AStudio/ui/A2AStudioPage';
import { SingleChatPage } from '../pages/SingleChat/ui/SingleChatPage';
import { LLMProvidersPage } from '../pages/LLMProviders/ui/LLMProvidersPage';
import { SystemConfigPage } from '../pages/SystemConfig/ui/SystemConfigPage';
import { SkillsPage } from '../pages/Skills/ui/SkillsPage';
import { Layout } from './Layout';

export const router = createBrowserRouter([
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
        path: 'a2a',
        element: <A2AStudioPage />,
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
]);
