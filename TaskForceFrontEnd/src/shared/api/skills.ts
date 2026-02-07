import { apiClient } from './client';

export interface Skill {
  id?: number;
  skillId: string;
  name: string;
  path: string;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface SkillImportRequest {
  gitUrl: string;
  branch?: string;
  targetDirectory?: string;
}

export const skillsApi = {
  /**
   * 列出所有 Skill
   */
  async list(): Promise<Skill[]> {
    const response = await apiClient.get<{ data: Skill[] }>('/api/skills');
    return response.data.data;
  },

  /**
   * 获取 Skill 详情
   */
  async getById(skillId: string): Promise<Skill> {
    const response = await apiClient.get<{ data: Skill }>(`/api/skills/${skillId}`);
    return response.data.data;
  },

  /**
   * 启用 Skill
   */
  async enable(skillId: string): Promise<void> {
    await apiClient.post(`/api/skills/${skillId}/enable`);
  },

  /**
   * 禁用 Skill
   */
  async disable(skillId: string): Promise<void> {
    await apiClient.post(`/api/skills/${skillId}/disable`);
  },

  /**
   * 从 Git 导入 Skill
   */
  async importFromGit(request: SkillImportRequest): Promise<void> {
    await apiClient.post('/api/skills/import', request);
  },
};
