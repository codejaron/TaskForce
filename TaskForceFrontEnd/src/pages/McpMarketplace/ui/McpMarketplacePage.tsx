import React, { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMcpStore } from '../../../features/mcp/model/store';
import {
  Server,
  Database,
  Plus,
  Trash2,
  Terminal,
  Globe,
  X,
  Search,
  CheckCircle,
  XCircle,
  ChevronDown,
  ChevronUp,
  Zap,
  Copy,
  Check,
  FileCode
} from 'lucide-react';
import type { ToolInfo } from '../../../shared/api';
import { clsx } from 'clsx';

export const McpMarketplacePage: React.FC = () => {
  const { t } = useTranslation();
  const { servers, tools, fetchServers, fetchTools, deleteServer } = useMcpStore();

  // Tool search and filter
  const [toolSearch, setToolSearch] = useState('');
  const [expandedServer, setExpandedServer] = useState<string | null>(null);

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

  // JSON Templates
  const TEMPLATE_PYTHON = `{
  "command": "python3",
  "args": ["-m", "mcp_server_name"],
  "description": "Python MCP 工具描述",
  "enabled": true
}`;

  const TEMPLATE_NPX = `{
  "command": "npx",
  "args": ["-y", "@modelcontextprotocol/server-name", "/path"],
  "description": "NPX MCP 工具描述",
  "enabled": true
}`;

  const TEMPLATE_LOCAL_SCRIPT = `{
  "command": "/usr/local/bin/python3",
  "args": ["-u", "/path/to/script.py"],
  "description": "本地脚本工具",
  "enabled": true,
  "env": {
    "PYTHONPATH": "/path/to/directory"
  }
}`;

  const TEMPLATE_ENV_VARS = `{
  "command": "python3",
  "args": ["-m", "mcp_server_github"],
  "description": "GitHub API 工具",
  "enabled": true,
  "env": {
    "GITHUB_TOKEN": "\${GITHUB_TOKEN}",
    "API_KEY": "\${API_KEY}"
  }
}`;

  // Handle adding tool from JSON
  const handleAddToolFromJson = async () => {
    try {
      // Validate JSON format
      let config;
      try {
        config = JSON.parse(jsonInput);
      } catch (e: unknown) {
        const error = e as Error;
        setJsonError('JSON 格式错误：' + error.message);
        return;
      }

      // Validate required fields
      if (!config.command) {
        setJsonError('缺少必填字段: command');
        return;
      }

      // Send to backend
      const response = await fetch('/api/mcp/json-config/add', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          toolKey: toolKeyInput.trim(),
          config: config
        })
      });

      if (!response.ok) {
        const error = await response.json();
        throw new Error(error.error || 'Failed to add tool');
      }

      // Close modal and reset
      setShowJsonEditor(false);
      setJsonInput('');
      setToolKeyInput('');
      setJsonError('');

      // Poll for the new tool to appear (backend needs time to reload config)
      const pollInterval = 300; // Check every 300ms
      const maxAttempts = 10; // Try for up to 3 seconds
      let attempts = 0;

      const checkForNewTool = async () => {
        await fetchServers();
        await fetchTools();
        attempts++;

        if (attempts < maxAttempts) {
          setTimeout(checkForNewTool, pollInterval);
        }
      };

      // Start polling after a short delay
      setTimeout(checkForNewTool, 500);

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
    const server = tool.serverName || 'Unknown';
    if (!acc[server]) acc[server] = [];
    acc[server].push(tool);
    return acc;
  }, {} as Record<string, ToolInfo[]>);

  return (
    <div className="h-screen overflow-y-auto bg-slate-50">
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

        {/* Connected Servers */}
        <div className="mb-10">
          <h2 className="text-lg font-semibold text-gray-900 mb-4 flex items-center gap-2">
            <Server size={20} className="text-orange-600" />
            {t('mcp.servers')} ({servers.length})
          </h2>

          {servers.length === 0 ? (
            <div className="bg-white rounded-2xl border border-gray-200 p-8 text-center shadow-sm">
              <div className="w-16 h-16 mx-auto mb-4 rounded-2xl bg-orange-50 flex items-center justify-center">
                <Server size={32} className="text-orange-600" />
              </div>
              <h3 className="text-lg font-semibold font-heading text-gray-900 mb-2">No Servers Connected</h3>
              <p className="text-gray-600 mb-4">Connect your first MCP server to start using tools</p>
              <button
                onClick={() => setShowJsonEditor(true)}
                className="inline-flex items-center gap-2 text-orange-600 hover:text-orange-700 cursor-pointer transition-colors duration-200"
              >
                <Plus size={16} />
                Connect Server
              </button>
            </div>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
              {servers.map(server => {
                const serverTools = tools.filter(t => t.serverName === server.name);

                return (
                  <div
                    key={server.id}
                    className="bg-white border border-gray-200 rounded-2xl overflow-hidden hover:border-gray-300 hover:shadow-md transition-all duration-200"
                  >
                    <div className="p-6">
                      <div className="flex justify-between items-start mb-4">
                        <div className="flex items-center gap-3">
                          <div className={clsx(
                            "w-12 h-12 rounded-xl flex items-center justify-center",
                            server.type === 'STDIO'
                              ? "bg-gradient-to-br from-blue-500 to-cyan-600"
                              : "bg-gradient-to-br from-green-500 to-emerald-600"
                          )}>
                            {server.type === 'STDIO' ? <Terminal size={24} className="text-white" /> : <Globe size={24} className="text-white" />}
                          </div>
                          <div>
                            <h3 className="font-bold text-lg text-white">{server.name}</h3>
                            <span className="text-xs text-gray-400">{server.type}</span>
                          </div>
                        </div>
                        <span className={clsx(
                          "flex items-center gap-1.5 px-2.5 py-1 text-xs rounded-full font-medium",
                          server.connected
                            ? "bg-green-500/10 text-green-400 border border-green-500/20"
                            : "bg-red-500/10 text-red-400 border border-red-500/20"
                        )}>
                          {server.connected ? <CheckCircle size={12} /> : <XCircle size={12} />}
                          {server.connected ? 'Connected' : 'Disconnected'}
                        </span>
                      </div>

                      {/* Command/URL */}
                      <div className="bg-black/30 rounded-xl p-3 font-mono text-xs text-gray-400 mb-4 overflow-x-auto">
                        {server.type === 'STDIO' ? (
                          <>
                            <span className="text-blue-400">{server.command}</span>
                            {server.args && server.args.length > 0 && (
                              <span className="text-gray-500"> {server.args.join(' ')}</span>
                            )}
                          </>
                        ) : (
                          <span className="text-green-400">{server.sseUrl}</span>
                        )}
                      </div>

                      {/* Tools count */}
                      <div className="flex items-center justify-between">
                        <span className="text-sm text-gray-400 flex items-center gap-1">
                          <Database size={14} />
                          {serverTools.length} tools
                        </span>
                        <button
                          onClick={() => server.id && deleteServer(server.id)}
                          className="p-2 hover:bg-red-500/20 rounded-lg text-gray-400 hover:text-red-400 transition-colors"
                        >
                          <Trash2 size={16} />
                        </button>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
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
                className="w-full bg-white border border-gray-300 rounded-xl pl-10 pr-4 py-2 text-sm text-gray-900 focus:ring-2 focus:ring-orange-500 focus:border-transparent outline-none shadow-sm"
              />
            </div>
          </div>

          {/* Tools by Server */}
          <div className="space-y-4">
            {Object.entries(toolsByServer).map(([serverName, serverTools]) => (
              <div key={serverName} className="bg-white border border-gray-200 rounded-2xl overflow-hidden shadow-sm">
                {/* Server Header */}
                <button
                  onClick={() => setExpandedServer(expandedServer === serverName ? null : serverName)}
                  className="w-full p-4 flex items-center justify-between hover:bg-gray-50 transition-colors duration-200 cursor-pointer"
                >
                  <div className="flex items-center gap-3">
                    <Server size={18} className="text-orange-600" />
                    <span className="font-medium text-gray-900">{serverName}</span>
                    <span className="text-xs text-gray-600 bg-gray-100 px-2 py-0.5 rounded-full">
                      {serverTools.length} {t('mcp.tools')}
                    </span>
                  </div>
                  {expandedServer === serverName ? (
                    <ChevronUp size={18} className="text-gray-600" />
                  ) : (
                    <ChevronDown size={18} className="text-gray-600" />
                  )}
                </button>

                {/* Tools List */}
                {expandedServer === serverName && (
                  <div className="border-t border-gray-200">
                    <table className="w-full text-left text-sm">
                      <thead className="bg-slate-50 text-gray-700">
                        <tr>
                          <th className="p-4 font-medium">{t('mcp.toolName')}</th>
                          <th className="p-4 font-medium">{t('mcp.description')}</th>
                          <th className="p-4 font-medium">ID</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-gray-200">
                        {serverTools.map(tool => (
                          <tr key={tool.id} className="hover:bg-slate-50 transition-colors duration-200">
                            <td className="p-4">
                              <span className="font-medium text-orange-600">{tool.name}</span>
                            </td>
                            <td className="p-4 text-gray-700 max-w-md truncate">{tool.description}</td>
                            <td className="p-4">
                              <div className="flex items-center gap-2">
                                <span className="font-mono text-xs text-gray-600 truncate max-w-[120px]">{tool.id}</span>
                                <button
                                  onClick={() => copyToClipboard(tool.id, tool.id)}
                                  className="p-1 hover:bg-gray-100 rounded transition-colors duration-200 cursor-pointer"
                                >
                                  {copiedId === tool.id ? (
                                    <Check size={12} className="text-green-600" />
                                  ) : (
                                    <Copy size={12} className="text-gray-600" />
                                  )}
                                </button>
                              </div>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            ))}

            {Object.keys(toolsByServer).length === 0 && (
              <div className="text-center py-12 text-gray-500">
                {toolSearch ? 'No tools match your search' : 'No tools available. Connect MCP servers first.'}
              </div>
            )}
          </div>
        </div>
      </div>

      {/* JSON Editor Modal */}
      {showJsonEditor && (
        <div className="fixed inset-0 bg-black/50 backdrop-blur-sm flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-2xl w-full max-w-3xl border border-gray-200 shadow-2xl max-h-[90vh] overflow-hidden flex flex-col">
            <div className="p-6 border-b border-gray-200 flex justify-between items-center">
              <div>
                <h2 className="text-2xl font-bold font-heading text-gray-900 flex items-center gap-2">
                  <FileCode size={24} className="text-purple-600" />
                  添加 MCP 工具 (JSON 配置)
                </h2>
                <p className="text-sm text-gray-600 mt-1">
                  粘贴标准 MCP 配置 JSON 快速添加工具
                </p>
              </div>
              <button onClick={() => setShowJsonEditor(false)} className="text-gray-600 hover:text-gray-900 transition-colors duration-200 cursor-pointer">
                <X size={24} />
              </button>
            </div>

            <div className="p-6 flex-1 overflow-y-auto">
              {/* Tool Key Input */}
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-2">
                  工具唯一标识 *
                </label>
                <input
                  type="text"
                  value={toolKeyInput}
                  onChange={e => setToolKeyInput(e.target.value)}
                  placeholder="例如: github, weather, filesystem"
                  className="w-full bg-white border border-gray-300 rounded-lg px-4 py-2 text-gray-900 focus:ring-2 focus:ring-purple-500 focus:border-transparent outline-none shadow-sm"
                />
                <p className="text-xs text-gray-500 mt-1">
                  用于在配置文件中唯一标识此工具，建议使用小写字母和下划线
                </p>
              </div>

              {/* JSON Input */}
              <div className="mb-4">
                <label className="block text-sm font-medium text-gray-700 mb-2">
                  工具配置 JSON *
                </label>
                <textarea
                  value={jsonInput}
                  onChange={e => {
                    setJsonInput(e.target.value);
                    setJsonError('');
                  }}
                  placeholder={`{
  "command": "python3",
  "args": ["-m", "mcp_server_github"],
  "description": "GitHub API 工具",
  "enabled": true,
  "env": {
    "GITHUB_TOKEN": "\${GITHUB_TOKEN}"
  }
}`}
                  className="w-full bg-white border border-gray-300 rounded-lg px-4 py-3 text-gray-900 font-mono text-sm focus:ring-2 focus:ring-purple-500 focus:border-transparent outline-none shadow-sm"
                  rows={15}
                />
                {jsonError && (
                  <div className="mt-2 p-3 bg-red-50 border border-red-200 rounded-lg">
                    <p className="text-red-700 text-sm">{jsonError}</p>
                  </div>
                )}
              </div>

              {/* Configuration Templates */}
              <div>
                <p className="text-sm font-medium text-gray-700 mb-2">快速模板：</p>
                <div className="grid grid-cols-2 gap-2">
                  <button
                    onClick={() => setJsonInput(TEMPLATE_PYTHON)}
                    className="text-left p-3 bg-white border border-gray-300 rounded-lg hover:border-purple-500 transition-colors duration-200 cursor-pointer"
                  >
                    <p className="text-gray-900 text-sm font-medium">Python MCP 工具</p>
                    <p className="text-gray-600 text-xs">已安装的 Python 包</p>
                  </button>
                  <button
                    onClick={() => setJsonInput(TEMPLATE_NPX)}
                    className="text-left p-3 bg-white border border-gray-300 rounded-lg hover:border-purple-500 transition-colors duration-200 cursor-pointer"
                  >
                    <p className="text-gray-900 text-sm font-medium">NPX MCP 工具</p>
                    <p className="text-gray-600 text-xs">NPM 包直接运行</p>
                  </button>
                  <button
                    onClick={() => setJsonInput(TEMPLATE_LOCAL_SCRIPT)}
                    className="text-left p-3 bg-white border border-gray-300 rounded-lg hover:border-purple-500 transition-colors duration-200 cursor-pointer"
                  >
                    <p className="text-gray-900 text-sm font-medium">本地脚本</p>
                    <p className="text-gray-600 text-xs">自定义 Python 脚本</p>
                  </button>
                  <button
                    onClick={() => setJsonInput(TEMPLATE_ENV_VARS)}
                    className="text-left p-3 bg-white border border-gray-300 rounded-lg hover:border-purple-500 transition-colors duration-200 cursor-pointer"
                  >
                    <p className="text-gray-900 text-sm font-medium">带环境变量</p>
                    <p className="text-gray-600 text-xs">使用环境变量占位符</p>
                  </button>
                </div>
              </div>
            </div>

            <div className="p-6 border-t border-gray-200 flex justify-end gap-3">
              <button
                onClick={() => setShowJsonEditor(false)}
                className="px-6 py-2.5 text-gray-700 hover:text-gray-900 hover:bg-gray-100 rounded-xl transition-colors duration-200 cursor-pointer"
              >
                取消
              </button>
              <button
                onClick={handleAddToolFromJson}
                disabled={!toolKeyInput.trim() || !jsonInput.trim()}
                className="px-6 py-2.5 bg-purple-600 hover:bg-purple-700 text-white rounded-xl transition-colors duration-200 font-medium disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2 cursor-pointer shadow-sm"
              >
                <Plus size={20} />
                添加工具
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
