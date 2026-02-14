import { api } from './client';

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
  async list(): Promise<Skill[]> {
    return api.skills.list();
  },

  async getById(skillId: string): Promise<Skill> {
    return api.skills.getById(skillId);
  },

  async enable(skillId: string): Promise<void> {
    await api.skills.enable(skillId);
  },

  async disable(skillId: string): Promise<void> {
    await api.skills.disable(skillId);
  },

  async importFromFolder(request: SkillImportRequest): Promise<void> {
    await api.skills.importFromGit({
      gitUrl: request.sourcePath,
      targetDirectory: request.targetDirectory
    });
  },

  async uploadSkill(files: File[], _targetDirectory?: string): Promise<void> {
    await api.skills.uploadSkill(files);
  },
};
