import React, { useState, useEffect } from 'react';
import {
  Users,
  Activity,
  Plus,
  X,
  ChevronLeft,
  ChevronRight,
  Trash2,
  Bot,
  MessageCircle,
  Loader2
} from 'lucide-react';
import { clsx } from 'clsx';
import { LeadChatPanel } from '../../../features/team-lead/ui';
import { useTeamStore } from '../../../features/team/model/store';
import { useAgentStore } from '../../../features/agents/model/store';

export const TeamStudioPage: React.FC = () => {

  // Team store
  const {
    sessions,
    currentSession,
    fetchSessions,
    createSession,
    selectSession,
    deleteSession
  } = useTeamStore();

  // Agent store
  const { agents, fetchAgents } = useAgentStore();

  // UI state
  const [showSidebar, setShowSidebar] = useState(true);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [newSessionName, setNewSessionName] = useState('');
  const [selectedAgentIds, setSelectedAgentIds] = useState<string[]>([]);
  const [isCreating, setIsCreating] = useState(false);

  // Filter agents - only show WORKER type agents to users
  const workerAgents = agents.filter(agent => {
    const roleType = typeof (agent as { roleType?: unknown }).roleType === 'string'
      ? (agent as { roleType?: string }).roleType
      : 'WORKER';
    return roleType === 'WORKER';
  });

  // Find the lead agent (will be automatically added to sessions)
  const leadAgent = agents.find(agent => {
    const roleType = typeof (agent as { roleType?: unknown }).roleType === 'string'
      ? (agent as { roleType?: string }).roleType
      : 'WORKER';
    return roleType === 'LEAD';
  });
  const leadAgentId = leadAgent?.id;

  // Load sessions and agents on mount
  useEffect(() => {
    fetchSessions();
    fetchAgents();
  }, [fetchSessions, fetchAgents]);

  const handleCreate = async () => {
    if (!newSessionName || selectedAgentIds.length < 1) {
      alert('Please enter a session name and select at least 1 agent');
      return;
    }

    setIsCreating(true);
    try {
      // Automatically add lead agent to the beginning of agent list if it exists
      const agentIdsToSend = leadAgentId
        ? [parseInt(leadAgentId), ...selectedAgentIds.map(id => parseInt(id))]
        : selectedAgentIds.map(id => parseInt(id));

      await createSession(newSessionName, agentIdsToSend);
      setShowCreateModal(false);
      setNewSessionName('');
      setSelectedAgentIds([]);
    } catch (error) {
      console.error('Failed to create session:', error);
      alert('Failed to create session');
    } finally {
      setIsCreating(false);
    }
  };

  const toggleAgentSelection = (agentId: string) => {
    setSelectedAgentIds(prev =>
      prev.includes(agentId)
        ? prev.filter(id => id !== agentId)
        : [...prev, agentId]
    );
  };

  const handleDelete = async (sessionId: string, e: React.MouseEvent) => {
    e.stopPropagation();

    if (window.confirm('Are you sure you want to delete this session?')) {
      try {
        await deleteSession(sessionId);
      } catch (error) {
        console.error('Failed to delete session:', error);
        alert('Failed to delete session');
      }
    }
  };

  return (
    <div className="h-full flex bg-white relative">
      {/* Sidebar - Sessions */}
      <div className={clsx(
        "flex-shrink-0 border-r border-gray-200 bg-gray-50 transition-all duration-300 relative",
        showSidebar ? "w-64" : "w-0 overflow-hidden"
      )}>
        <div className="h-full flex flex-col w-64">
          {/* Header */}
          <div className="p-4 border-b border-gray-200">
            <div className="flex items-center justify-between mb-4">
              <div className="flex items-center gap-2">
                <Users size={20} className="text-purple-600" />
                <h2 className="font-bold text-gray-900">Team Sessions</h2>
              </div>
              <button
                onClick={() => setShowCreateModal(true)}
                className="p-2 bg-purple-50 hover:bg-purple-100 text-purple-600 rounded-lg transition-colors duration-200 cursor-pointer"
              >
                <Plus size={18} />
              </button>
            </div>
          </div>

          {/* Sessions List */}
          <div className="flex-1 overflow-y-auto p-3 space-y-2">
            {sessions.length === 0 ? (
              <div className="text-center py-8">
                <MessageCircle size={32} className="mx-auto mb-3 text-gray-400" />
                <p className="text-sm text-gray-500">No sessions yet</p>
                <button
                  onClick={() => setShowCreateModal(true)}
                  className="mt-3 text-sm text-purple-600 hover:text-purple-700 cursor-pointer"
                >
                  Create your first session
                </button>
              </div>
            ) : (
              sessions.map((session) => (
                <div
                  key={session.id}
                  className={clsx(
                    "relative group w-full rounded-lg transition-colors duration-200",
                    currentSession?.id === session.id
                      ? "bg-purple-50 border border-purple-200"
                      : "hover:bg-gray-100 border border-transparent"
                  )}
                >
                  <button
                    onClick={() => selectSession(session)}
                    className="w-full px-3 py-2 text-left cursor-pointer"
                  >
                    <span className="font-medium text-gray-900 text-sm truncate block pr-8">{session.name}</span>
                  </button>
                  <button
                    onClick={(e) => handleDelete(session.id, e)}
                    className="absolute right-2 top-1/2 -translate-y-1/2 p-1.5 opacity-0 group-hover:opacity-100 hover:bg-red-50 hover:text-red-600 text-gray-400 rounded transition-all duration-200 cursor-pointer"
                    title="Delete session"
                  >
                    <Trash2 size={14} />
                  </button>
                </div>
              ))
            )}
          </div>
        </div>
      </div>

      {/* Toggle Sidebar Button */}
      <button
        onClick={() => setShowSidebar(!showSidebar)}
        className={clsx(
          "absolute top-1/2 -translate-y-1/2 z-10 p-1 bg-white border border-gray-200 rounded-r-lg text-gray-600 hover:text-gray-900 transition-colors duration-200 shadow-sm cursor-pointer",
          showSidebar ? "left-[254px]" : "left-0"
        )}
      >
        {showSidebar ? <ChevronLeft size={16} /> : <ChevronRight size={16} />}
      </button>

      {/* Main Content */}
      <div className="flex-1 flex flex-col">
        {currentSession ? (
          <>
            {/* Session Header */}
            <div className="p-4 border-b border-gray-200 bg-white">
              <h1 className="text-xl font-bold text-gray-900">{currentSession.name}</h1>
              <p className="text-sm text-gray-500">Team Studio</p>
            </div>

            {/* 三栏布局 */}
            <div className="flex-1 flex overflow-hidden">
              {/* 左侧：Lead 对话区 (30%) */}
              <div className="w-[30%] border-r border-gray-200 flex flex-col bg-gray-50">
                <LeadChatPanel />
              </div>

              {/* 中间：Task Board 看板 (40%) */}
              <div className="w-[40%] border-r border-gray-200 flex flex-col bg-white">
                {/* 区域标题 */}
                <div className="flex-shrink-0 px-4 py-3 border-b border-gray-200">
                  <div className="flex items-center gap-2">
                    <Activity size={20} className="text-blue-600" />
                    <h2 className="font-semibold text-gray-900">Task Board</h2>
                  </div>
                </div>

                {/* 看板内容区域 */}
                <div className="flex-1 overflow-y-auto p-4">
                  <div className="h-full flex items-center justify-center">
                    <div className="text-center text-gray-500">
                      <Activity size={48} className="mx-auto mb-3 text-gray-300" />
                      <p className="text-sm">暂无任务</p>
                    </div>
                  </div>
                </div>
              </div>

              {/* 右侧：Worker 监控区 (30%) */}
              <div className="w-[30%] flex flex-col bg-gray-50">
                {/* 区域标题 */}
                <div className="flex-shrink-0 px-4 py-3 border-b border-gray-200 bg-white">
                  <div className="flex items-center gap-2">
                    <Users size={20} className="text-green-600" />
                    <h2 className="font-semibold text-gray-900">Worker 监控</h2>
                  </div>
                </div>

                {/* 监控内容区域 */}
                <div className="flex-1 overflow-y-auto p-4">
                  <div className="h-full flex items-center justify-center">
                    <div className="text-center text-gray-500">
                      <Users size={48} className="mx-auto mb-3 text-gray-300" />
                      <p className="text-sm">暂无 Worker</p>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </>
        ) : (
          <div className="flex-1 flex items-center justify-center">
            <div className="text-center">
              <MessageCircle size={64} className="mx-auto mb-4 text-gray-300" />
              <h3 className="text-xl font-medium text-gray-700 mb-2">No Session Selected</h3>
              <p className="text-sm text-gray-500 mb-4">Create or select a session to start</p>
              <button
                onClick={() => setShowCreateModal(true)}
                className="px-6 py-2.5 bg-purple-600 hover:bg-purple-700 text-white rounded-xl font-medium transition-colors duration-200 cursor-pointer"
              >
                Create New Session
              </button>
            </div>
          </div>
        )}
      </div>

      {/* Create Session Modal */}
      {showCreateModal && (
        <div className="fixed inset-0 bg-black/50 backdrop-blur-sm flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-2xl w-full max-w-lg border border-gray-200 shadow-2xl">
            <div className="p-6 border-b border-gray-200 flex justify-between items-center">
              <h2 className="text-2xl font-bold text-gray-900">Create Team Session</h2>
              <button
                onClick={() => setShowCreateModal(false)}
                className="text-gray-600 hover:text-gray-900 transition-colors duration-200 cursor-pointer"
              >
                <X size={24} />
              </button>
            </div>

            <div className="p-6 space-y-5">
              {/* Session Name */}
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">Session Name *</label>
                <input
                  type="text"
                  value={newSessionName}
                  onChange={e => setNewSessionName(e.target.value)}
                  className="w-full bg-white border border-gray-300 rounded-xl px-4 py-3 text-gray-900 focus:ring-2 focus:ring-purple-500 focus:border-transparent outline-none"
                  placeholder="Enter session name"
                />
              </div>

              {/* Select Agents */}
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">
                  Select Agents *
                  <span className="text-xs text-gray-500 ml-2">(Built-in Lead Agent included • min 1)</span>
                </label>
                <div className="grid grid-cols-2 gap-2 max-h-48 overflow-y-auto bg-gray-50 border border-gray-200 rounded-xl p-3">
                  {workerAgents.length === 0 ? (
                    <p className="text-sm text-gray-500 text-center py-4 col-span-2">
                      No agents available
                    </p>
                  ) : (
                    workerAgents.map(agent => (
                      <button
                        key={agent.id}
                        type="button"
                        onClick={() => toggleAgentSelection(agent.id)}
                        className={clsx(
                          "p-3 rounded-xl border transition-colors duration-200 text-left cursor-pointer",
                          selectedAgentIds.includes(agent.id)
                            ? "border-purple-300 bg-purple-50"
                            : "border-gray-200 hover:border-gray-300"
                        )}
                      >
                        <div className="flex items-center gap-2">
                          <Bot size={16} className={selectedAgentIds.includes(agent.id) ? "text-purple-600" : "text-gray-500"} />
                          <span className={clsx(
                            "text-sm font-medium truncate",
                            selectedAgentIds.includes(agent.id) ? "text-gray-900" : "text-gray-600"
                          )}>
                            {agent.name}
                          </span>
                        </div>
                      </button>
                    ))
                  )}
                </div>
              </div>
            </div>

            <div className="p-6 border-t border-gray-200 flex justify-end gap-3">
              <button
                onClick={() => setShowCreateModal(false)}
                className="px-6 py-2.5 text-gray-700 hover:text-gray-900 hover:bg-gray-100 rounded-xl transition-colors duration-200 cursor-pointer"
              >
                Cancel
              </button>
              <button
                onClick={handleCreate}
                disabled={isCreating || !newSessionName || selectedAgentIds.length < 1}
                className="px-6 py-2.5 bg-purple-600 hover:bg-purple-700 text-white rounded-xl font-medium transition-colors duration-200 cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2"
              >
                {isCreating && <Loader2 size={16} className="animate-spin" />}
                {isCreating ? 'Creating...' : 'Create Session'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
