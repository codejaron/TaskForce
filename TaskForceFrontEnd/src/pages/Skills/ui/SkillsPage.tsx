import React, { useEffect, useState } from 'react';
import { api } from '../../../shared/api';
import { useTranslation } from 'react-i18next';
import { GitBranch, Power, PowerOff, Loader2, CheckCircle, XCircle } from 'lucide-react';
import { clsx } from 'clsx';

interface Skill {
  skillId: string;
  name: string;
  path: string;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
}

interface SkillImportRequest {
  gitUrl: string;
  branch?: string;
  targetDirectory?: string;
}

export const SkillsPage: React.FC = () => {
  const { t } = useTranslation();
  const [skills, setSkills] = useState<Skill[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showImportDialog, setShowImportDialog] = useState(false);
  const [importForm, setImportForm] = useState<SkillImportRequest>({
    gitUrl: '',
    branch: 'main',
    targetDirectory: ''
  });
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

  const handleImport = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      setImporting(true);
      setError(null);
      await api.skills.importFromGit(importForm);
      setShowImportDialog(false);
      setImportForm({ gitUrl: '', branch: 'main', targetDirectory: '' });
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
            <GitBranch className="w-4 h-4" />
            Import from Git
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
            <p className="text-lg mb-2">No skills found</p>
            <p className="text-sm">Import skills from Git to get started</p>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {skills.map((skill) => (
              <div
                key={skill.skillId}
                className={clsx(
                  'bg-white dark:bg-gray-800 rounded-lg border p-4 transition-all',
                  skill.enabled
                    ? 'border-green-200 dark:border-green-800'
                    : 'border-gray-200 dark:border-gray-700 opacity-60'
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
                        : 'bg-gray-100 dark:bg-gray-700 text-gray-400 hover:bg-gray-200 dark:hover:bg-gray-600'
                    )}
                    title={skill.enabled ? 'Disable' : 'Enable'}
                  >
                    {skill.enabled ? <Power className="w-4 h-4" /> : <PowerOff className="w-4 h-4" />}
                  </button>
                </div>

                <div className="space-y-2 text-sm">
                  <div className="flex items-center gap-2">
                    {skill.enabled ? (
                      <CheckCircle className="w-4 h-4 text-green-500" />
                    ) : (
                      <XCircle className="w-4 h-4 text-gray-400" />
                    )}
                    <span className="text-gray-600 dark:text-gray-400">
                      {skill.enabled ? 'Enabled' : 'Disabled'}
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
              <h2 className="text-lg font-semibold text-gray-900 dark:text-white">Import Skill from Git</h2>
            </div>

            <form onSubmit={handleImport} className="p-6 space-y-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Git URL *</label>
                <input
                  type="text"
                  value={importForm.gitUrl}
                  onChange={(e) => setImportForm({ ...importForm, gitUrl: e.target.value })}
                  placeholder="https://github.com/user/skill-repo.git"
                  className="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 rounded-lg bg-white dark:bg-gray-700 text-gray-900 dark:text-white"
                  required
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Branch</label>
                <input
                  type="text"
                  value={importForm.branch}
                  onChange={(e) => setImportForm({ ...importForm, branch: e.target.value })}
                  placeholder="main"
                  className="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 rounded-lg bg-white dark:bg-gray-700 text-gray-900 dark:text-white"
                />
              </div>

              <div>
                <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">Target Directory (optional)</label>
                <input
                  type="text"
                  value={importForm.targetDirectory}
                  onChange={(e) => setImportForm({ ...importForm, targetDirectory: e.target.value })}
                  placeholder="/path/to/skills"
                  className="w-full px-3 py-2 border border-gray-300 dark:border-gray-600 rounded-lg bg-white dark:bg-gray-700 text-gray-900 dark:text-white"
                />
              </div>

              <div className="flex gap-3 pt-4">
                <button
                  type="button"
                  onClick={() => setShowImportDialog(false)}
                  className="flex-1 px-4 py-2 border border-gray-300 dark:border-gray-600 rounded-lg text-gray-700 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-700"
                  disabled={importing}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="flex-1 px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
                  disabled={importing}
                >
                  {importing ? (
                    <>
                      <Loader2 className="w-4 h-4 animate-spin" />
                      Importing...
                    </>
                  ) : (
                    'Import'
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
