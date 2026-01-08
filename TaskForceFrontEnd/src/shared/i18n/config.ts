import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import LanguageDetector from 'i18next-browser-languagedetector';
import en from './locales/en';
import zh from './locales/zh';

const resources = {
  en: {
    translation: en,
  },
  zh: {
    translation: zh,
  },
};

console.log('[i18n] Initializing with resources:', Object.keys(resources));

i18n
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    resources,
    fallbackLng: 'en',
    lng: 'zh', // 默认语言设置为中文
    debug: true, // 开启调试模式
    interpolation: {
      escapeValue: false,
    },
    detection: {
      order: ['localStorage', 'navigator'],
      caches: ['localStorage'],
      lookupLocalStorage: 'i18nextLng',
    },
  })
  .then(() => {
    console.log('[i18n] Initialized successfully. Current language:', i18n.language);
    console.log('[i18n] Available languages:', i18n.languages);
  });

export default i18n;

