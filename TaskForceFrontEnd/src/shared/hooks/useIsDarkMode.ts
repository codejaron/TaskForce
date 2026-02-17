import { useEffect, useState } from 'react';

export const useIsDarkMode = (): boolean => {
  const getDarkMode = () => {
    if (typeof document === 'undefined') {
      return false;
    }
    return document.documentElement.classList.contains('dark');
  };

  const [isDarkMode, setIsDarkMode] = useState<boolean>(getDarkMode);

  useEffect(() => {
    const root = document.documentElement;
    const observer = new MutationObserver(() => {
      setIsDarkMode(root.classList.contains('dark'));
    });

    observer.observe(root, { attributes: true, attributeFilter: ['class'] });
    return () => observer.disconnect();
  }, []);

  return isDarkMode;
};
