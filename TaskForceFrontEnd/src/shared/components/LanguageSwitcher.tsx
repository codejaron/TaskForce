import React from 'react';
import { useTranslation } from 'react-i18next';
import { Globe } from 'lucide-react';

export const LanguageSwitcher: React.FC = () => {
  const { i18n } = useTranslation();

  const currentLang = i18n.language || 'en';
  const isZh = currentLang.startsWith('zh');

  const toggleLanguage = () => {
    const newLang = isZh ? 'en' : 'zh';
    console.log('[i18n] Switching language from', currentLang, 'to', newLang);
    i18n.changeLanguage(newLang);
  };

  const displayText = isZh ? '中文' : 'English';

  return (
    <button
      onClick={toggleLanguage}
      className="flex items-center gap-2 px-3 py-2 hover:bg-white/10 rounded-lg transition-colors text-sm w-full"
      title={isZh ? '切换到英文' : 'Switch to Chinese'}
    >
      <Globe size={16} className="text-gray-400" />
      <span className="text-gray-300">{displayText}</span>
    </button>
  );
};
