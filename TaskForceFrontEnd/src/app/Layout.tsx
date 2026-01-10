import React, { useState } from 'react';
import { Outlet } from 'react-router-dom';
import { Sidebar } from '../widgets/Sidebar/ui/Sidebar';
import { Menu } from 'lucide-react';

export const Layout: React.FC = () => {
  const [isSidebarOpen, setIsSidebarOpen] = useState(true);

  return (
    <div className="flex h-screen bg-white text-gray-900 overflow-hidden">
      {/* Sidebar */}
      <Sidebar
        isOpen={isSidebarOpen}
        toggleSidebar={() => setIsSidebarOpen(!isSidebarOpen)}
      />

      {/* Main Content */}
      <div className="flex-1 flex flex-col relative h-full">
        {/* Mobile Header */}
        <header className="md:hidden flex items-center p-4 border-b border-gray-200 bg-white z-10">
          <button
            onClick={() => setIsSidebarOpen(true)}
            className="text-gray-600 hover:text-gray-900"
          >
            <Menu size={24} />
          </button>
          <span className="ml-4 font-medium text-gray-900"> MCP</span>
        </header>


        {/* Page Content */}
        <main className="flex-1 overflow-y-auto relative">
          <Outlet />
        </main>
      </div>
    </div>
  );
};
