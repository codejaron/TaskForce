import React, { useEffect, useState } from 'react';
import { Outlet } from 'react-router-dom';
import { Sidebar } from '../widgets/Sidebar/ui/Sidebar';
import { Menu } from 'lucide-react';

export type ThemeMode = 'light' | 'dark';

const THEME_STORAGE_KEY = 'taskforce-theme-mode';

const getInitialTheme = (): ThemeMode => {
  if (typeof window === 'undefined') {
    return 'light';
  }
  const savedTheme = window.localStorage.getItem(THEME_STORAGE_KEY);
  if (savedTheme === 'light' || savedTheme === 'dark') {
    return savedTheme;
  }
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
};

export const Layout: React.FC = () => {
  const [isSidebarOpen, setIsSidebarOpen] = useState(true);
  const [themeMode, setThemeMode] = useState<ThemeMode>(getInitialTheme);

  useEffect(() => {
    const root = document.documentElement;
    root.classList.toggle('dark', themeMode === 'dark');
    root.style.colorScheme = themeMode;
    window.localStorage.setItem(THEME_STORAGE_KEY, themeMode);
  }, [themeMode]);

  const toggleThemeMode = () => {
    setThemeMode((currentTheme) => (currentTheme === 'dark' ? 'light' : 'dark'));
  };

  return (
    <div className="flex h-screen bg-white dark:bg-neutral-950 text-gray-900 dark:text-neutral-100 overflow-hidden transition-colors">
      {/* Sidebar */}
      <Sidebar
        isOpen={isSidebarOpen}
        toggleSidebar={() => setIsSidebarOpen(!isSidebarOpen)}
        themeMode={themeMode}
        toggleThemeMode={toggleThemeMode}
      />

      {/* Main Content */}
      <div className="flex-1 flex flex-col relative h-full">
        {/* Mobile Header */}
        <header className="md:hidden flex items-center p-4 border-b border-gray-200 dark:border-neutral-800 bg-white dark:bg-neutral-950 z-10">
          <button
            onClick={() => setIsSidebarOpen(true)}
            className="text-gray-600 dark:text-neutral-300 hover:text-gray-900 dark:hover:text-white"
          >
            <Menu size={24} />
          </button>
          <span className="ml-4 font-medium text-gray-900 dark:text-neutral-100"> MCP</span>
        </header>


        {/* Page Content */}
        <main className="flex-1 overflow-y-auto relative bg-white dark:bg-neutral-950 transition-colors">
          <Outlet />
        </main>
      </div>

    </div>
  );
};
