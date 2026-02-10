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
  sourcePath: string;
  targetDirectory?: string;
  copyMode?: boolean;
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
   * 从本地文件夹导入 Skill
   */
  async importFromFolder(request: SkillImportRequest): Promise<void> {
    await apiClient.post('/api/skills/import', request);
  },

  /**
   * 上传文件夹导入 Skill
   */
  async uploadSkill(files: File[], targetDirectory?: string): Promise<void> {
    const formData = new FormData();

    // 添加所有文件
    files.forEach(file => {
      formData.append('files', file);
    });

    // 添加目标目录（可选）
    if (targetDirectory) {
      formData.append('targetDirectory', targetDirectory);
    }

    await apiClient.post('/api/skills/upload', formData, {
      headers: {
        'Content-Type': 'multipart/form-data',
      },
    });
  },
};
