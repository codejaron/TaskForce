import React, { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMcpStore } from '../../../features/mcp/model/store';
import { api } from '../../../shared/api';
import { clsx } from 'clsx';
import {
  Server,
  Database,
  Plus,
  Trash2,
  X,
  Search,
  ChevronDown,
  ChevronUp,
  Zap,
  Copy,
  Check,
  FileCode
} from 'lucide-react';
import type { ToolInfo } from '../../../shared/api';

export const McpMarketplacePage: React.FC = () => {
  const { t } = useTranslation();
  const { servers, tools, fetchServers, fetchTools, deleteServer } = useMcpStore();

  // Tool search and filter
  const [toolSearch, setToolSearch] = useState('');
  const [expandedServer, setExpandedServer] = useState<string | null>(null);
  const [deletingServerId, setDeletingServerId] = useState<string | null>(null);

  // Copy state
  const [copiedId, setCopiedId] = useState<string | null>(null);

  // JSON Editor state
  const [showJsonEditor, setShowJsonEditor] = useState(false);
  const [jsonInput, setJsonInput] = useState('');
  const [jsonError, setJsonError] = useState('');
  const [toolKeyInput, setToolKeyInput] = useState('');

  useEffect(() => {
    fetchServers();
    fetchTools();
  }, [fetchServers, fetchTools]);

  const copyToClipboard = (text: string, id: string) => {
    navigator.clipboard.writeText(text);
    setCopiedId(id);
    setTimeout(() => setCopiedId(null), 2000);
  };

  const handleDeleteServer = async (serverId: string, serverName: string) => {
    if (serverId === 'native') {
      return;
    }
    const confirmed = window.confirm(t('mcp.confirmDeleteServer', { name: serverName }));
    if (!confirmed) {
      return;
    }

    try {
      setDeletingServerId(serverId);
      await deleteServer(serverId);
      if (expandedServer === serverId) {
        setExpandedServer(null);
      }
    } catch (error) {
      console.error('Failed to delete MCP server:', error);
      alert(t('mcp.deleteServerFailed'));
    } finally {
      setDeletingServerId(null);
    }
  };

  // Handle adding tool from JSON
  const handleAddToolFromJson = async () => {
    try {
      // Validate JSON format
      let config;
      try {
        config = JSON.parse(jsonInput);
      } catch (e: unknown) {
        const error = e as Error;
        setJsonError(t('mcp.jsonFormatError') + error.message);
        return;
      }

      // Validate required fields
      if (!config.command && !config.sseUrl) {
        setJsonError(t('mcp.missingRequiredFields'));
        return;
      }

      // Auto-detect type based on config (compatible with common formats)
      const type = config.sseUrl ? 'SSE' : 'STDIO';
      
      // Generate name from toolKey or command
      let serverName = toolKeyInput.trim();
      if (!serverName) {
        // Auto-generate name from command or description
        if (config.command) {
          const cmdParts = config.command.split('/');
          serverName = cmdParts[cmdParts.length - 1];
          if (config.args && config.args.length > 0) {
            const firstArg = config.args[0];
            if (firstArg.startsWith('-m')) {
              serverName = config.args[1] || serverName;
            } else if (firstArg.includes('server')) {
              serverName = firstArg.replace(/[@\/]/g, '-');
            }
          }
        } else if (config.sseUrl) {
          const url = new URL(config.sseUrl);
          serverName = url.hostname.split('.')[0];
        }
        serverName = serverName || 'unnamed-server';
      }
      
      // Build server config for new API (only include fields that exist in original config)
      const serverConfig: any = {
        name: serverName,
        type: type,
        enabled: config.enabled !== false,
        description: config.description || ''
      };

      if (type === 'STDIO') {
        serverConfig.command = config.command;
        if (config.args) serverConfig.args = config.args;
        if (config.env) serverConfig.env = config.env;
      } else {
        serverConfig.sseUrl = config.sseUrl;
        if (config.headers) serverConfig.headers = config.headers;
        if (config.timeout) serverConfig.timeout = config.timeout;
      }

      // Use the new API
      await api.mcp.registerServer(serverConfig);

      // Close modal and reset
      setShowJsonEditor(false);
      setJsonInput('');
      setToolKeyInput('');
      setJsonError('');

      // Refresh data
      await fetchServers();
      await fetchTools();

    } catch (error: unknown) {
      const err = error as Error;
      setJsonError(err.message);
    }
  };

  const filteredTools = tools.filter(
    tool =>
      tool.name.toLowerCase().includes(toolSearch.toLowerCase()) ||
      tool.description.toLowerCase().includes(toolSearch.toLowerCase()) ||
      tool.serverName.toLowerCase().includes(toolSearch.toLowerCase())
  );

  // Group tools by server
  const toolsByServer = filteredTools.reduce((acc, tool) => {
    const serverId = tool.serverId || tool.serverName || 'unknown';
    const serverName = tool.serverName || tool.serverId || 'Unknown';
    if (!acc[serverId]) {
      acc[serverId] = { serverId, serverName, tools: [] as ToolInfo[] };
    }
    acc[serverId].tools.push(tool);
    return acc;
  }, {} as Record<string, { serverId: string; serverName: string; tools: ToolInfo[] }>);

  if (!toolSearch.trim()) {
    servers.forEach((server) => {
      const serverId = server.id || server.name;
      if (!toolsByServer[serverId]) {
        toolsByServer[serverId] = {
          serverId,
          serverName: server.name,
          tools: []
        };
      }
    });
  }

  const serverEntries = Object.values(toolsByServer).sort((a, b) => {
    if (a.serverId === 'native') return -1;
    if (b.serverId === 'native') return 1;
    return a.serverName.localeCompare(b.serverName);
  });

  return (
    <div className="h-screen overflow-y-auto bg-slate-50 dark:bg-neutral-950">
      <div className="max-w-7xl mx-auto p-8">
        {/* Header */}
        <div className="flex justify-between items-start mb-8">
          <div>
            <div className="flex items-center gap-3 mb-2">
              <div className="w-10 h-10 rounded-xl bg-orange-600 flex items-center justify-center shadow-sm">
                <Database size={20} className="text-white" />
              </div>
              <h1 className="text-3xl font-bold font-heading text-gray-900">{t('mcp.title')}</h1>
            </div>
            <p className="text-gray-600">{t('dashboard.connectMcpServerDesc')}</p>
          </div>
          <button
            onClick={() => setShowJsonEditor(true)}
            className="flex items-center gap-2 bg-purple-600 hover:bg-purple-700 text-white px-6 py-3 rounded-xl font-medium transition-colors duration-200 shadow-sm cursor-pointer"
            title="Add MCP Tool via JSON Configuration"
          >
            <FileCode size={20} />
            {t('mcp.addServer')}
          </button>
        </div>

        {/* Available Tools */}
        <div>
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-lg font-semibold text-gray-900 flex items-center gap-2">
              <Zap size={20} className="text-yellow-600" />
              {t('mcp.availableTools')} ({tools.length})
            </h2>

            {/* Search */}
            <div className="relative w-64">
              <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
              <input
                type="text"
                value={toolSearch}
                onChange={e => setToolSearch(e.target.value)}
                placeholder={t('mcp.searchTools')}
                className="w-full bg-white dark:bg-neutral-900 border border-gray-300 dark:border-neutral-700 rounded-xl pl-10 pr-4 py-2 text-sm text-gray-900 dark:text-neutral-100 focus:ring-2 focus:ring-orange-500 focus:border-transparent outline-none shadow-sm"
              />
            </div>
          </div>

          {/* Tools by Server */}
          <div className="space-y-4">
            {serverEntries.map(({ serverId, serverName, tools: serverTools }) => (
              <div key={serverId} className="bg-white dark:bg-neutral-950 border border-gray-200 dark:border-neutral-800 rounded-2xl overflow-hidden shadow-sm">
                {/* Server Header */}
                <button
                  onClick={() => setExpandedServer(expandedServer === serverId ? null : serverId)}
                  className={clsx(
                    "w-full p-4 flex items-center justify-between transition-colors duration-200 cursor-pointer",
                    expandedServer === serverId
                      ? "bg-gray-50 dark:bg-neutral-900"
                      : "bg-white dark:bg-neutral-950 hover:bg-gray-50 dark:hover:bg-neutral-900"
                  )}
                >
                  <div className="flex items-center gap-3">
                    <Server size={18} className="text-orange-600" />
                    <span className="font-medium text-gray-900 dark:text-neutral-100">{serverName}</span>
                    <span className="text-xs text-gray-600 dark:text-neutral-300 bg-gray-100 dark:bg-neutral-800 px-2 py-0.5 rounded-full">
                      {serverTools.length} {t('mcp.tools')}
                    </span>
                  </div>
                  <div className="flex items-center gap-2">
                    {serverId !== 'native' && (
                      <button
                        type="button"
                        onClick={(e) => {
                          e.stopPropagation();
                          handleDeleteServer(serverId, serverName);
                        }}
                        disabled={deletingServerId === serverId}
                        className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs text-red-700 dark:text-red-200 bg-red-50 dark:bg-red-950/25 hover:bg-red-100 dark:hover:bg-red-900/35 border border-red-200 dark:border-red-800/60 rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
                        title={t('mcp.delete')}
                      >
                        <Trash2 size={14} />
                        {deletingServerId === serverId ? t('common.loading') : t('mcp.delete')}
                      </button>
                    )}
                    {expandedServer === serverId ? (
                      <ChevronUp size={18} className="text-gray-600 dark:text-neutral-300" />
                    ) : (
                      <ChevronDown size={18} className="text-gray-600 dark:text-neutral-300" />
                    )}
                  </div>
                </button>

                {/* Tools List */}
                {expandedServer === serverId && (
                  <div className="border-t border-gray-200 dark:border-neutral-800">
                    {serverTools.length === 0 ? (
                      <div className="p-6 text-sm text-gray-500 dark:text-neutral-400">{t('mcp.noTools')}</div>
                    ) : (
                      <table className="w-full text-left text-sm">
                        <thead className="bg-slate-50 dark:bg-neutral-900 text-gray-700 dark:text-neutral-300">
                          <tr>
                            <th className="p-4 font-medium">{t('mcp.toolName')}</th>
                            <th className="p-4 font-medium">{t('mcp.description')}</th>
                            <th className="p-4 font-medium">ID</th>
                          </tr>
                        </thead>
                        <tbody className="divide-y divide-gray-200 dark:divide-neutral-800">
                          {serverTools.map(tool => (
                            <tr key={tool.id} className="hover:bg-slate-50 dark:hover:bg-neutral-900 transition-colors duration-200">
                              <td className="p-4">
                                <span className="font-medium text-orange-600 dark:text-neutral-200">{tool.name}</span>
                              </td>
                              <td className="p-4 text-gray-700 dark:text-neutral-300 max-w-md truncate">{tool.description}</td>
                              <td className="p-4">
                                <div className="flex items-center gap-2">
                                  <span className="font-mono text-xs text-gray-600 dark:text-neutral-400 truncate max-w-[120px]">{tool.id}</span>
                                  <button
                                    onClick={() => copyToClipboard(tool.id, tool.id)}
                                    className="p-1 hover:bg-gray-100 dark:hover:bg-neutral-800 rounded transition-colors duration-200 cursor-pointer"
                                  >
                                    {copiedId === tool.id ? (
                                      <Check size={12} className="text-green-600" />
                                    ) : (
                                      <Copy size={12} className="text-gray-600 dark:text-neutral-400" />
                                    )}
                                  </button>
                                </div>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    )}
                  </div>
                )}
              </div>
            ))}

            {serverEntries.length === 0 && (
              <div className="text-center py-12 text-gray-500 dark:text-neutral-400">
                {toolSearch ? t('mcp.noToolsMatch') : t('mcp.noToolsAvailable')}
              </div>
            )}
          </div>
        </div>
      </div>

      {/* JSON Editor Modal */}
      {showJsonEditor && (
        <div className="fixed inset-0 bg-black/50 backdrop-blur-sm flex items-center justify-center z-50 p-4">
          <div className="bg-white dark:bg-neutral-900 rounded-2xl w-full max-w-3xl border border-gray-200 dark:border-neutral-800 shadow-2xl max-h-[90vh] overflow-hidden flex flex-col">
            <div className="p-6 border-b border-gray-200 dark:border-neutral-800 flex justify-between items-center">
              <div>
                <h2 className="text-2xl font-bold font-heading text-gray-900 dark:text-neutral-100 flex items-center gap-2">
                  <FileCode size={24} className="text-purple-600" />
                  {t('mcp.addToolJsonTitle')}
                </h2>
                <p className="text-sm text-gray-600 dark:text-neutral-400 mt-1">
                  {t('mcp.addToolJsonDesc')}
                </p>
              </div>
              <button onClick={() => setShowJsonEditor(false)} className="text-gray-600 dark:text-neutral-400 hover:text-gray-900 dark:hover:text-white transition-colors duration-200 cursor-pointer">
                <X size={24} />
              </button>
            </div>

            <div className="p-6 flex-1 overflow-y-auto">
              {/* Tool Key Input */}
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-2">
                  {t('mcp.toolKeyLabel')} *
                </label>
                <input
                  type="text"
                  value={toolKeyInput}
                  onChange={e => setToolKeyInput(e.target.value)}
                  placeholder={t('mcp.toolKeyPlaceholder')}
                  className="w-full bg-white dark:bg-neutral-950 border border-gray-300 dark:border-neutral-700 rounded-lg px-4 py-2 text-gray-900 dark:text-neutral-100 focus:ring-2 focus:ring-purple-500 focus:border-transparent outline-none shadow-sm"
                />
                <p className="text-xs text-gray-500 dark:text-neutral-400 mt-1">
                  {t('mcp.toolKeyHint')}
                </p>
              </div>

              {/* JSON Input */}
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-2">
                  {t('mcp.toolConfigLabel')} *
                </label>
                <textarea
                  value={jsonInput}
                  onChange={e => {
                    setJsonInput(e.target.value);
                    setJsonError('');
                  }}
                  placeholder={`{
  "command": "npx",
  "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
  "description": "Filesystem MCP Server",
  "enabled": true
}`}
                  className="w-full bg-white dark:bg-neutral-950 border border-gray-300 dark:border-neutral-700 rounded-lg px-4 py-3 text-gray-900 dark:text-neutral-100 font-mono text-sm focus:ring-2 focus:ring-purple-500 focus:border-transparent outline-none shadow-sm"
                  rows={15}
                />
                {jsonError && (
                  <div className="mt-2 p-3 bg-red-50 dark:bg-red-950/25 border border-red-200 dark:border-red-800/60 rounded-lg">
                    <p className="text-red-700 dark:text-red-200 text-sm">{jsonError}</p>
                  </div>
                )}
              </div>
            </div>

            <div className="p-6 border-t border-gray-200 dark:border-neutral-800 flex justify-end gap-3">
              <button
                onClick={() => setShowJsonEditor(false)}
                className="px-6 py-2.5 text-gray-700 dark:text-neutral-300 hover:text-gray-900 dark:hover:text-white hover:bg-gray-100 dark:hover:bg-neutral-800 rounded-xl transition-colors duration-200 cursor-pointer"
              >
                {t('common.cancel')}
              </button>
              <button
                onClick={handleAddToolFromJson}
                disabled={!toolKeyInput.trim() || !jsonInput.trim()}
                className="px-6 py-2.5 bg-purple-600 hover:bg-purple-700 text-white rounded-xl transition-colors duration-200 font-medium disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2 cursor-pointer shadow-sm"
              >
                <Plus size={20} />
                {t('mcp.addTool')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
