import React from 'react';
import { useTranslation } from 'react-i18next';
import { Globe } from 'lucide-react';
import { clsx } from 'clsx';

interface LanguageSwitcherProps {
  className?: string;
}

export const LanguageSwitcher: React.FC<LanguageSwitcherProps> = ({ className }) => {
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
      className={clsx(
        "flex items-center justify-center gap-2 px-3 py-2 rounded-lg border border-gray-200 dark:border-neutral-700 bg-white dark:bg-neutral-900 hover:bg-gray-100 dark:hover:bg-neutral-800 transition-colors text-sm text-gray-700 dark:text-neutral-200",
        className
      )}
      title={isZh ? '切换到英文' : 'Switch to Chinese'}
    >
      <Globe size={16} className="text-gray-500 dark:text-neutral-400" />
      <span>{displayText}</span>
    </button>
  );
};
