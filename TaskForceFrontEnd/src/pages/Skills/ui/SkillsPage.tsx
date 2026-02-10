import React, { useEffect, useState } from 'react';
import { api } from '../../../shared/api';
import { useTranslation } from 'react-i18next';
import { FolderOpen, Power, PowerOff, Loader2, CheckCircle, XCircle, Upload } from 'lucide-react';
import { clsx } from 'clsx';

interface Skill {
  skillId: string;
  name: string;
  path: string;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export const SkillsPage: React.FC = () => {
  const { t } = useTranslation();
  const [skills, setSkills] = useState<Skill[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showImportDialog, setShowImportDialog] = useState(false);
  const [selectedFiles, setSelectedFiles] = useState<File[]>([]);
  const [importing, setImporting] = useState(false);

  const loadSkills = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await api.skills.list();
      setSkills(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load skills');
      console.error('Failed to load skills:', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadSkills();
  }, []);

  const handleToggleSkill = async (skill: Skill) => {
    try {
      if (skill.enabled) {
        await api.skills.disable(skill.skillId);
      } else {
        await api.skills.enable(skill.skillId);
      }
      await loadSkills();
    } catch (err) {
      console.error('Failed to toggle skill:', err);
      setError(err instanceof Error ? err.message : 'Failed to toggle skill');
    }
  };

  const handleFolderSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (files && files.length > 0) {
      const fileArray = Array.from(files);
      setSelectedFiles(fileArray);
      setError(null);
    }
  };

  const handleImport = async (e: React.FormEvent) => {
    e.preventDefault();

    if (selectedFiles.length === 0) {
      setError('请选择一个文件夹');
      return;
    }

    try {
      setImporting(true);
      setError(null);

      await api.skills.uploadSkill(selectedFiles);

      setShowImportDialog(false);
      setSelectedFiles([]);
      await loadSkills();
    } catch (err) {
      console.error('Failed to import skill:', err);
      setError(err instanceof Error ? err.message : 'Failed to import skill');
    } finally {
      setImporting(false);
    }
  };

  return (
    <div className="h-full flex flex-col bg-gray-50 dark:bg-gray-900">
      <div className="bg-white dark:bg-gray-800 border-b border-gray-200 dark:border-gray-700 px-6 py-4">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-gray-900 dark:text-white">Skills Management</h1>
            <p className="text-sm text-gray-600 dark:text-gray-400 mt-1">Manage agent skills and capabilities</p>
          </div>
          <button
            onClick={() => setShowImportDialog(true)}
            className="flex items-center gap-2 px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors"
          >
            <FolderOpen className="w-4 h-4" />
            导入本地文件夹
          </button>
        </div>
      </div>

      {error && (
        <div className="mx-6 mt-4 p-4 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg">
          <p className="text-sm text-red-800 dark:text-red-200">{error}</p>
        </div>
      )}

      <div className="flex-1 overflow-auto p-6">
        {loading ? (
          <div className="flex items-center justify-center h-64">
            <Loader2 className="w-8 h-8 animate-spin text-gray-400" />
          </div>
        ) : skills.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-64 text-gray-500">
            <p className="text-lg mb-2">暂无技能</p>
            <p className="text-sm">导入本地文件夹以开始使用</p>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {skills.map((skill) => (
              <div
                key={skill.skillId}
                className={clsx(
                  'rounded-lg border p-4 transition-all',
                  skill.enabled
                    ? 'bg-white dark:bg-gray-800 border-green-200 dark:border-green-800'
                    : 'bg-red-50 dark:bg-red-900/20 border-red-300 dark:border-red-800'
                )}
              >
                <div className="flex items-start justify-between mb-3">
                  <div className="flex-1">
                    <h3 className="font-semibold text-gray-900 dark:text-white mb-1">{skill.name}</h3>
                    <p className="text-xs text-gray-500 dark:text-gray-400 font-mono">{skill.skillId}</p>
                  </div>
                  <button
                    onClick={() => handleToggleSkill(skill)}
                    className={clsx(
                      'p-2 rounded-lg transition-colors',
                      skill.enabled
                        ? 'bg-green-100 dark:bg-green-900/30 text-green-600 dark:text-green-400 hover:bg-green-200 dark:hover:bg-green-900/50'
                        : 'bg-red-100 dark:bg-red-900/30 text-red-600 dark:text-red-400 hover:bg-red-200 dark:hover:bg-red-900/50'
                    )}
                    title={skill.enabled ? '禁用' : '启用'}
                  >
                    {skill.enabled ? <Power className="w-4 h-4" /> : <PowerOff className="w-4 h-4" />}
                  </button>
                </div>

                <div className="space-y-2 text-sm">
                  <div className="flex items-center gap-2">
                    {skill.enabled ? (
                      <CheckCircle className="w-4 h-4 text-green-500" />
                    ) : (
                      <XCircle className="w-4 h-4 text-red-500" />
                    )}
                    <span className={clsx(
                      'font-medium',
                      skill.enabled
                        ? 'text-green-600 dark:text-green-400'
                        : 'text-red-600 dark:text-red-400'
                    )}>
                      {skill.enabled ? '已启用' : '未启用'}
                    </span>
                  </div>
                  <div className="text-xs text-gray-500 dark:text-gray-400">
                    <p className="truncate" title={skill.path}>📁 {skill.path}</p>
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {showImportDialog && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
          <div className="bg-white dark:bg-gray-800 rounded-lg shadow-xl w-full max-w-md mx-4">
            <div className="px-6 py-4 border-b border-gray-200 dark:border-gray-700">
              <h2 className="text-lg font-semibold text-gray-900 dark:text-white">导入 Skill</h2>
            </div>

            <form onSubmit={handleImport} className="p-6 space-y-4">
              {/* Skill 结构说明 */}
              <div className="bg-gray-50 dark:bg-gray-900/50 border border-gray-200 dark:border-gray-700 rounded-lg p-4">
                <p className="text-xs font-medium text-gray-700 dark:text-gray-300 mb-2">Skill 文件夹结构：</p>
                <pre className="text-xs text-gray-600 dark:text-gray-400 font-mono leading-relaxed">
{`skill-name/
├── SKILL.md          # 必需：核心定义文件
├── scripts/          # 可选：可执行脚本
├── references/       # 可选：补充参考文档
└── assets/           # 可选：静态资源`}
                </pre>
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
                  选择 Skill 文件夹 *
                </label>
                <div className="relative">
                  <input
                    type="file"
                    // @ts-ignore - webkitdirectory is not in TypeScript types but is widely supported
                    webkitdirectory="true"
                    directory="true"
                    multiple
                    onChange={handleFolderSelect}
                    className="hidden"
                    id="folder-input"
                  />
                  <label
                    htmlFor="folder-input"
                    className="flex items-center justify-center gap-2 w-full px-4 py-3 border-2 border-dashed border-gray-300 dark:border-gray-600 rounded-lg cursor-pointer hover:border-blue-500 dark:hover:border-blue-400 transition-colors bg-gray-50 dark:bg-gray-700/50"
                  >
                    <FolderOpen className="w-5 h-5 text-gray-400" />
                    <span className="text-sm text-gray-600 dark:text-gray-300">
                      {selectedFiles.length > 0
                        ? `已选择 ${selectedFiles.length} 个文件`
                        : '点击选择文件夹'}
                    </span>
                  </label>
                </div>
              </div>

              {selectedFiles.length > 0 && (
                <div className="bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-800 rounded-lg p-3">
                  <div className="flex items-start gap-2">
                    <CheckCircle className="w-4 h-4 text-blue-600 dark:text-blue-400 mt-0.5 flex-shrink-0" />
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium text-blue-900 dark:text-blue-100">
                        文件夹已选择
                      </p>
                      <p className="text-xs text-blue-700 dark:text-blue-300 mt-1 truncate">
                        {selectedFiles[0]?.webkitRelativePath?.split('/')[0] || '未知文件夹'}
                      </p>
                    </div>
                  </div>
                </div>
              )}

              <div className="flex gap-3 pt-4">
                <button
                  type="button"
                  onClick={() => {
                    setShowImportDialog(false);
                    setSelectedFiles([]);
                    setError(null);
                  }}
                  className="flex-1 px-4 py-2 border border-gray-300 dark:border-gray-600 rounded-lg text-gray-700 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-700"
                  disabled={importing}
                >
                  取消
                </button>
                <button
                  type="submit"
                  className="flex-1 px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
                  disabled={importing || selectedFiles.length === 0}
                >
                  {importing ? (
                    <>
                      <Loader2 className="w-4 h-4 animate-spin" />
                      上传中...
                    </>
                  ) : (
                    <>
                      <Upload className="w-4 h-4" />
                      导入
                    </>
                  )}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
