import React, { useEffect, useMemo, useState } from 'react';
import {
  Users,
  Activity,
  Plus,
  FolderOpen,
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
import { WorkerChatPanel } from '../../../features/team/ui/WorkerChatPanel';
import { useTeamStore } from '../../../features/team/model/store';
import { useAgentStore } from '../../../features/agents/model/store';
import { desktopClient } from '../../../shared/desktop/client';

export const TeamStudioPage: React.FC = () => {

  // Team store
  const {
    sessions,
    currentSession,
    fetchSessions,
    createSession,
    selectSession,
    deleteSession,
    tasks,
    members,
    activeWorkerId,
    setActiveWorker
  } = useTeamStore();

  // Agent store
  const { agents, fetchAgents } = useAgentStore();

  // UI state
  const [showSidebar, setShowSidebar] = useState(true);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [newSessionName, setNewSessionName] = useState('');
  const [selectedAgentIds, setSelectedAgentIds] = useState<string[]>([]);
  const [isCreating, setIsCreating] = useState(false);
  const [showTaskBoard, setShowTaskBoard] = useState(false);
  const [selectedProjectPath, setSelectedProjectPath] = useState<string | null>(null);
  const [isPickingProjectDirectory, setIsPickingProjectDirectory] = useState(false);

  // Filter agents - only show WORKER type agents to users
  const workerAgents = agents.filter(agent => {
    const roleType = typeof (agent as { roleType?: unknown }).roleType === 'string'
      ? (agent as { roleType?: string }).roleType
      : 'WORKER';
    return roleType === 'WORKER';
  });

  // Load sessions and agents on mount
  useEffect(() => {
    fetchSessions();
    fetchAgents();
  }, [fetchSessions, fetchAgents]);

  useEffect(() => {
    setShowTaskBoard(false);
  }, [currentSession?.id]);

  useEffect(() => {
    if (!desktopClient.isDesktop() || !currentSession?.id) {
      setSelectedProjectPath(null);
      return;
    }

    void desktopClient.getSelectedProjectDirectory(currentSession.id)
      .then(path => setSelectedProjectPath(path))
      .catch(() => setSelectedProjectPath(null));
  }, [currentSession?.id]);

  const ownerNameById = useMemo(
    () => new Map(members.map(member => [member.instanceId, member.agentName])),
    [members]
  );

  const blockedTargetsByTaskId = useMemo(() => {
    const map = new Map<number, number[]>();
    tasks.forEach(task => {
      task.blockedBy.forEach(depId => {
        const current = map.get(depId) || [];
        map.set(depId, [...current, task.taskId]);
      });
    });
    return map;
  }, [tasks]);

  const dependencySummary = useMemo(
    () =>
      tasks
        .filter(task => task.blockedBy.length > 0)
        .map(task => ({
          taskId: task.taskId,
          blockedBy: task.blockedBy
        })),
    [tasks]
  );

  const formatOwner = (owner?: string) => {
    if (!owner) return '未分配';
    const displayName = ownerNameById.get(owner);
    if (displayName) return displayName;
    const workerSuffix = owner.match(/_w(\d+)$/i);
    if (workerSuffix) return `Worker #${workerSuffix[1]}`;
    if (owner.length > 20) return `${owner.slice(0, 8)}...${owner.slice(-4)}`;
    return owner;
  };

  const handleCreate = async () => {
    if (!newSessionName || selectedAgentIds.length < 1) {
      alert('Please enter a session name and select at least 1 agent');
      return;
    }

    setIsCreating(true);
    try {
      // Lead agent is automatically added by backend (PLANNER role)
      const agentIdsToSend = selectedAgentIds.map(id => parseInt(id));

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

  const handleBindProject = async () => {
    if (!currentSession) {
      return;
    }

    setIsPickingProjectDirectory(true);
    try {
      const selection = await desktopClient.pickProjectDirectory(currentSession.id);
      if (selection.canceled || !selection.path) {
        return;
      }
      setSelectedProjectPath(selection.path);
    } catch (error) {
      console.error('Failed to bind local project:', error);
      alert(error instanceof Error ? error.message : 'Failed to bind local project');
    } finally {
      setIsPickingProjectDirectory(false);
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
              <div className="flex items-start justify-between gap-3">
                <div>
                  <h1 className="text-xl font-bold text-gray-900">{currentSession.name}</h1>
                  <p className="text-sm text-gray-500">Team Studio</p>
                </div>
                <div className="flex flex-col items-end gap-2">
                  {desktopClient.isDesktop() && (
                    <button
                      onClick={handleBindProject}
                      disabled={isPickingProjectDirectory}
                      className={clsx(
                        "px-3 py-2 rounded-xl text-sm font-medium transition-colors cursor-pointer flex items-center gap-2 border",
                        selectedProjectPath
                          ? "border-emerald-200 bg-emerald-50 text-emerald-700 hover:bg-emerald-100"
                          : "border-amber-200 bg-amber-50 text-amber-700 hover:bg-amber-100",
                        isPickingProjectDirectory && "opacity-70 cursor-wait"
                      )}
                    >
                      {isPickingProjectDirectory
                        ? <Loader2 size={16} className="animate-spin" />
                        : <FolderOpen size={16} />
                      }
                      <span>{selectedProjectPath ? '项目目录已选择' : '选择项目目录'}</span>
                    </button>
                  )}

                  <button
                    onClick={() => setShowTaskBoard(true)}
                    className="px-3 py-2 border border-blue-200 bg-blue-50 text-blue-700 rounded-xl text-sm font-medium hover:bg-blue-100 transition-colors cursor-pointer flex items-center gap-2"
                  >
                    <Activity size={16} />
                    <span>Task Board</span>
                    <span className="text-xs px-1.5 py-0.5 rounded-md bg-blue-100 text-blue-800">
                      {tasks.filter(t => t.status === 'COMPLETED').length}/{tasks.length}
                    </span>
                  </button>
                </div>
              </div>
              {desktopClient.isDesktop() && (
                <p className={clsx(
                  "mt-2 text-xs",
                  selectedProjectPath ? "text-emerald-700" : "text-amber-700"
                )}>
                  {selectedProjectPath
                    ? `项目目录: ${selectedProjectPath}`
                    : '尚未选择项目目录'}
                </p>
              )}
            </div>

            <div className="flex-1 flex overflow-hidden relative flex-col lg:flex-row">
              {/* 左侧：Lead 对话区 */}
              <div className="h-1/2 lg:h-full lg:w-1/2 border-b lg:border-b-0 lg:border-r border-gray-200 flex flex-col bg-gray-50">
                <LeadChatPanel />
              </div>

              {/* 右侧：Worker 对话区 */}
              <div className="h-1/2 lg:h-full lg:w-1/2 flex flex-col bg-gray-50">
                {/* Worker Tab 头 */}
                <div className="flex-shrink-0 px-2 py-2 border-b border-gray-200 bg-white overflow-x-auto">
                  <div className="flex items-center gap-1">
                    <Users size={16} className="text-green-600 shrink-0 mr-1" />
                    {members.length === 0 ? (
                      <span className="text-xs text-gray-400">暂无 Worker</span>
                    ) : (
                      members.map(m => (
                        <button
                          key={m.instanceId}
                          onClick={() => setActiveWorker(
                            activeWorkerId === m.instanceId ? null : m.instanceId
                          )}
                          className={clsx(
                            "px-3 py-1.5 rounded-lg text-xs font-medium transition-colors whitespace-nowrap cursor-pointer",
                            activeWorkerId === m.instanceId
                              ? "bg-green-100 text-green-800 border border-green-300"
                              : "bg-gray-100 text-gray-600 hover:bg-gray-200"
                          )}
                        >
                          <span className={clsx(
                            "inline-block w-1.5 h-1.5 rounded-full mr-1.5",
                            m.status === 'BUSY' ? "bg-blue-500" :
                            m.status === 'ERROR' ? "bg-red-500" :
                            m.status === 'STOPPED' ? "bg-gray-400" :
                            "bg-green-500"
                          )} />
                          {m.agentName}
                        </button>
                      ))
                    )}
                  </div>
                </div>

                {/* Worker 对话窗口 */}
                <div className="flex-1 overflow-hidden">
                  <WorkerChatPanel />
                </div>
              </div>

              <div
                className={clsx(
                  "absolute inset-0 bg-black/20 z-20 transition-opacity duration-200",
                  showTaskBoard ? "opacity-100" : "opacity-0 pointer-events-none"
                )}
                onClick={() => setShowTaskBoard(false)}
              />

              <aside className={clsx(
                "absolute top-0 right-0 h-full w-full sm:w-[420px] max-w-[95vw] bg-white border-l border-gray-200 z-30 shadow-xl transform transition-transform duration-300 flex flex-col",
                showTaskBoard ? "translate-x-0" : "translate-x-full"
              )}>
                <div className="flex items-center gap-2 px-4 py-3 border-b border-gray-200">
                  <Activity size={18} className="text-blue-600" />
                  <h2 className="font-semibold text-gray-900">Task Board</h2>
                  <span className="text-xs text-gray-400 ml-auto">
                    {tasks.filter(t => t.status === 'COMPLETED').length}/{tasks.length}
                  </span>
                  <button
                    onClick={() => setShowTaskBoard(false)}
                    className="p-1.5 rounded-md hover:bg-gray-100 text-gray-500 hover:text-gray-700 cursor-pointer"
                    title="Close"
                  >
                    <X size={16} />
                  </button>
                </div>

                <div className="flex-1 overflow-y-auto p-4 space-y-2">
                  {tasks.length === 0 ? (
                    <div className="h-full flex items-center justify-center">
                      <div className="text-center text-gray-500">
                        <Activity size={48} className="mx-auto mb-3 text-gray-300" />
                        <p className="text-sm">暂无任务</p>
                      </div>
                    </div>
                  ) : (
                    <>
                      {dependencySummary.length > 0 && (
                        <div className="p-3 rounded-lg border border-indigo-200 bg-indigo-50">
                          <p className="text-[11px] uppercase tracking-wide text-indigo-700 font-semibold">
                            依赖关系图（简版）
                          </p>
                          <div className="mt-1.5 space-y-1">
                            {dependencySummary.map(item => (
                              <p key={item.taskId} className="text-xs text-indigo-800">
                                {item.blockedBy.map(depId => `#${depId}`).join(' + ')} {'->'} #{item.taskId}
                              </p>
                            ))}
                          </div>
                        </div>
                      )}

                      {tasks.map(task => {
                        const blockedBy = task.blockedBy;
                        const blocks = blockedTargetsByTaskId.get(task.taskId) || task.blocks;

                        return (
                          <div key={task.taskId} className={clsx(
                            "p-3 rounded-lg border transition-all",
                            task.status === 'COMPLETED' ? "bg-green-50 border-green-200" :
                            task.status === 'WORKING' || task.status === 'ASSIGNED' ? "bg-blue-50 border-blue-200" :
                            task.status === 'FAILED' ? "bg-red-50 border-red-200" :
                            "bg-white border-gray-200"
                          )}>
                            <div className="flex items-center justify-between">
                              <span className="text-sm font-medium text-gray-900">
                                #{task.taskId} {task.subject}
                              </span>
                              <span className={clsx(
                                "text-xs px-2 py-0.5 rounded-full font-medium",
                                task.status === 'COMPLETED' ? "bg-green-100 text-green-700" :
                                task.status === 'WORKING' ? "bg-blue-100 text-blue-700" :
                                task.status === 'ASSIGNED' ? "bg-yellow-100 text-yellow-700" :
                                task.status === 'FAILED' ? "bg-red-100 text-red-700" :
                                "bg-gray-100 text-gray-600"
                              )}>
                                {task.status}
                              </span>
                            </div>
                            {task.description && (
                              <p className="text-xs text-gray-500 mt-1 line-clamp-2">{task.description}</p>
                            )}
                            {task.completionNote && (
                              <p className="text-xs text-emerald-700 mt-1">
                                completionNote: {task.completionNote}
                              </p>
                            )}
                            <p className="text-xs text-gray-500 mt-1">负责人: {formatOwner(task.owner)}</p>
                            {blockedBy.length > 0 && (
                              <p className="text-xs text-amber-700 mt-1">
                                被阻塞于: {blockedBy.map(depId => `#${depId}`).join(', ')}
                              </p>
                            )}
                            {blocks.length > 0 && (
                              <p className="text-xs text-blue-700 mt-1">
                                正在阻塞: {blocks.map(depId => `#${depId}`).join(', ')}
                              </p>
                            )}
                          </div>
                        );
                      })}
                    </>
                  )}
                </div>
              </aside>
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
                  <span className="text-xs text-gray-500 ml-2">(Lead Agent auto-added • min 1)</span>
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
