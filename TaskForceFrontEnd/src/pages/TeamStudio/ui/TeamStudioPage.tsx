import React, { useEffect, useMemo, useState } from 'react';
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
import { useTranslation } from 'react-i18next';
import { LeadChatPanel } from '../../../features/team-lead/ui';
import { WorkerChatPanel } from '../../../features/team/ui/WorkerChatPanel';
import { useTeamStore } from '../../../features/team/model/store';
import { useAgentStore } from '../../../features/agents/model/store';

export const TeamStudioPage: React.FC = () => {
  const { t } = useTranslation();

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
    if (!owner) return t('team.unassigned');
    const displayName = ownerNameById.get(owner);
    if (displayName) return displayName;
    const workerSuffix = owner.match(/_w(\d+)$/i);
    if (workerSuffix) return t('team.workerWithIndex', { index: workerSuffix[1] });
    if (owner.length > 20) return `${owner.slice(0, 8)}...${owner.slice(-4)}`;
    return owner;
  };

  const handleCreate = async () => {
    if (!newSessionName || selectedAgentIds.length < 1) {
      alert(t('team.createSessionValidation'));
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
      alert(t('team.createSessionFailed'));
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

    if (window.confirm(t('team.confirmDeleteSession'))) {
      try {
        await deleteSession(sessionId);
      } catch (error) {
        console.error('Failed to delete session:', error);
        alert(t('team.deleteSessionFailed'));
      }
    }
  };

  const lifecycleLabel = (status: 'RUNNING' | 'STOPPED' | 'DESTROYED') => {
    if (status === 'RUNNING') return t('team.lifecycleRunning');
    if (status === 'DESTROYED') return t('team.lifecycleDestroyed');
    return t('team.lifecycleStopped');
  };

  const lifecycleDotClass = (status: 'RUNNING' | 'STOPPED' | 'DESTROYED') => {
    if (status === 'RUNNING') return 'bg-emerald-500';
    if (status === 'DESTROYED') return 'bg-gray-500';
    return 'bg-amber-500';
  };

  return (
    <div className="h-full flex bg-white dark:bg-neutral-950 relative">
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
                <h2 className="font-bold text-gray-900">{t('team.sessions')}</h2>
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
                <p className="text-sm text-gray-500">{t('team.noSessions')}</p>
                <button
                  onClick={() => setShowCreateModal(true)}
                  className="mt-3 text-sm text-purple-600 hover:text-purple-700 cursor-pointer"
                >
                  {t('team.createFirstSession')}
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
                    title={t('team.deleteSession')}
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
            <div className="p-4 border-b border-gray-200 dark:border-neutral-800 bg-white dark:bg-neutral-950">
              <div className="flex items-start justify-between gap-3">
                <div>
                  <h1 className="text-xl font-bold text-gray-900">{currentSession.name}</h1>
                  <p className="text-sm text-gray-500">{t('team.studioSubtitle')}</p>
                </div>
                <div className="flex items-center gap-2">
                  <button
                    onClick={() => setShowTaskBoard(true)}
                    className="px-3 py-2 border border-blue-200 dark:border-neutral-700 bg-blue-50 dark:bg-neutral-900 text-blue-700 dark:text-neutral-100 rounded-xl text-sm font-medium hover:bg-blue-100 dark:hover:bg-neutral-800 transition-colors cursor-pointer flex items-center gap-2"
                  >
                    <Activity size={16} />
                    <span>{t('team.taskBoard')}</span>
                    <span className="text-xs px-1.5 py-0.5 rounded-md bg-blue-100 dark:bg-neutral-800 text-blue-800 dark:text-neutral-200">
                      {tasks.filter(t => t.status === 'COMPLETED').length}/{tasks.length}
                    </span>
                  </button>
                </div>
              </div>
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
                  <div className="flex items-start gap-2">
                    <Users size={16} className="text-green-600 shrink-0 mr-1" />
                    {members.length === 0 ? (
                      <span className="text-xs text-gray-400">{t('team.noWorker')}</span>
                    ) : (
                      members.map(m => (
                        <button
                          key={m.instanceId}
                          onClick={() => setActiveWorker(
                            activeWorkerId === m.instanceId ? null : m.instanceId
                          )}
                          className={clsx(
                            "min-w-[180px] max-w-[220px] px-3 py-2 rounded-xl border transition-colors cursor-pointer text-left shrink-0",
                            activeWorkerId === m.instanceId
                              ? "bg-green-100 text-green-800 border-green-300"
                              : "bg-gray-100 text-gray-700 border-gray-200 hover:bg-gray-200"
                          )}
                        >
                          <span className="block w-full truncate text-sm font-semibold leading-5">
                            {m.agentName}
                          </span>
                          <span className={clsx(
                            "mt-1 inline-flex items-center gap-1 text-[11px] leading-4",
                            activeWorkerId === m.instanceId ? "text-green-700/80" : "text-gray-500"
                          )}>
                            <span className={clsx(
                              "inline-block w-1.5 h-1.5 rounded-full",
                              lifecycleDotClass(m.lifecycleStatus)
                            )} />
                            <span>{lifecycleLabel(m.lifecycleStatus)}</span>
                          </span>
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
                "absolute top-0 right-0 h-full w-full sm:w-[420px] max-w-[95vw] bg-white dark:bg-neutral-950 border-l border-gray-200 dark:border-neutral-800 z-30 shadow-xl transform transition-transform duration-300 flex flex-col",
                showTaskBoard ? "translate-x-0" : "translate-x-full"
              )}>
                <div className="flex items-center gap-2 px-4 py-3 border-b border-gray-200 dark:border-neutral-800">
                  <Activity size={18} className="text-blue-600 dark:text-neutral-300" />
                  <h2 className="font-semibold text-gray-900 dark:text-neutral-100">{t('team.taskBoard')}</h2>
                  <span className="text-xs text-gray-400 dark:text-neutral-500 ml-auto">
                    {tasks.filter(t => t.status === 'COMPLETED').length}/{tasks.length}
                  </span>
                  <button
                    onClick={() => setShowTaskBoard(false)}
                    className="p-1.5 rounded-md hover:bg-gray-100 dark:hover:bg-neutral-800 text-gray-500 dark:text-neutral-400 hover:text-gray-700 dark:hover:text-neutral-200 cursor-pointer"
                    title={t('team.close')}
                  >
                    <X size={16} />
                  </button>
                </div>

                <div className="flex-1 overflow-y-auto p-4 space-y-2">
                  {tasks.length === 0 ? (
                    <div className="h-full flex items-center justify-center">
                      <div className="text-center text-gray-500">
                        <Activity size={48} className="mx-auto mb-3 text-gray-300" />
                        <p className="text-sm">{t('team.noTasks')}</p>
                      </div>
                    </div>
                  ) : (
                    <>
                      {dependencySummary.length > 0 && (
                        <div className="p-3 rounded-lg border border-indigo-200 dark:border-neutral-700 bg-indigo-50 dark:bg-neutral-900">
                          <p className="text-[11px] uppercase tracking-wide text-indigo-700 dark:text-neutral-200 font-semibold">
                            {t('team.dependencyGraph')}
                          </p>
                          <div className="mt-1.5 space-y-1">
                            {dependencySummary.map(item => (
                              <p key={item.taskId} className="text-xs text-indigo-800 dark:text-neutral-300">
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
                            task.status === 'COMPLETED' ? "bg-green-50 dark:bg-green-950/25 border-green-200 dark:border-green-800/60" :
                            task.status === 'WORKING' ? "bg-blue-50 dark:bg-blue-950/25 border-blue-200 dark:border-blue-800/60" :
                            task.status === 'ASSIGNED' ? "bg-amber-50 dark:bg-amber-950/25 border-amber-200 dark:border-amber-800/60" :
                            task.status === 'FAILED' ? "bg-red-50 dark:bg-red-950/25 border-red-200 dark:border-red-800/60" :
                            "bg-white dark:bg-neutral-900 border-gray-200 dark:border-neutral-700"
                          )}>
                            <div className="flex items-center justify-between">
                              <span className="text-sm font-medium text-gray-900 dark:text-neutral-100">
                                #{task.taskId} {task.subject}
                              </span>
                              <span className={clsx(
                                "text-xs px-2 py-0.5 rounded-full font-medium",
                                task.status === 'COMPLETED' ? "bg-green-100 dark:bg-green-900/35 text-green-700 dark:text-green-200" :
                                task.status === 'WORKING' ? "bg-blue-100 dark:bg-blue-900/35 text-blue-700 dark:text-[#bfdbfe]" :
                                task.status === 'ASSIGNED' ? "bg-yellow-100 dark:bg-amber-900/35 text-yellow-700 dark:text-[#fde68a]" :
                                task.status === 'FAILED' ? "bg-red-100 dark:bg-red-900/35 text-red-700 dark:text-red-200" :
                                "bg-gray-100 dark:bg-neutral-800 text-gray-600 dark:text-neutral-300"
                              )}>
                                {task.status}
                              </span>
                            </div>
                            {task.description && (
                              <p className="text-xs text-gray-500 dark:text-neutral-400 mt-1 line-clamp-2">{task.description}</p>
                            )}
                            {task.completionNote && (
                              <p className="text-xs text-emerald-700 dark:text-emerald-300 mt-1">
                                completionNote: {task.completionNote}
                              </p>
                            )}
                            <p className="text-xs text-gray-500 dark:text-neutral-400 mt-1">{t('team.owner')}: {formatOwner(task.owner)}</p>
                            {blockedBy.length > 0 && (
                              <p className="text-xs text-amber-700 dark:text-[#fde68a] mt-1">
                                {t('team.blockedBy')}: {blockedBy.map(depId => `#${depId}`).join(', ')}
                              </p>
                            )}
                            {blocks.length > 0 && (
                              <p className="text-xs text-blue-700 dark:text-[#bfdbfe] mt-1">
                                {t('team.blocking')}: {blocks.map(depId => `#${depId}`).join(', ')}
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
              <h3 className="text-xl font-medium text-gray-700 mb-2">{t('team.noSessionSelected')}</h3>
              <p className="text-sm text-gray-500 mb-4">{t('team.createOrSelectSession')}</p>
              <button
                onClick={() => setShowCreateModal(true)}
                className="px-6 py-2.5 bg-purple-600 hover:bg-purple-700 text-white rounded-xl font-medium transition-colors duration-200 cursor-pointer"
              >
                {t('team.createNewSession')}
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
              <h2 className="text-2xl font-bold text-gray-900">{t('team.createTeamSession')}</h2>
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
                <label className="block text-sm font-medium text-gray-700 mb-2">{t('team.sessionName')} *</label>
                <input
                  type="text"
                  value={newSessionName}
                  onChange={e => setNewSessionName(e.target.value)}
                  className="w-full bg-white border border-gray-300 rounded-xl px-4 py-3 text-gray-900 focus:ring-2 focus:ring-purple-500 focus:border-transparent outline-none"
                  placeholder={t('team.enterSessionName')}
                />
              </div>

              {/* Select Agents */}
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">
                  {t('team.selectAgents')} *
                  <span className="text-xs text-gray-500 ml-2">({t('team.leadAutoAddedMin1')})</span>
                </label>
                <div className="grid grid-cols-2 gap-2 max-h-48 overflow-y-auto bg-gray-50 border border-gray-200 rounded-xl p-3">
                  {workerAgents.length === 0 ? (
                    <p className="text-sm text-gray-500 text-center py-4 col-span-2">
                      {t('team.noAgentsAvailable')}
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
                {t('team.cancel')}
              </button>
              <button
                onClick={handleCreate}
                disabled={isCreating || !newSessionName || selectedAgentIds.length < 1}
                className="px-6 py-2.5 bg-purple-600 hover:bg-purple-700 text-white rounded-xl font-medium transition-colors duration-200 cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2"
              >
                {isCreating && <Loader2 size={16} className="animate-spin" />}
                {isCreating ? t('team.creating') : t('team.createSession')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
