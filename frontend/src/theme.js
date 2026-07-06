import { useEffect, useState } from 'react';

const KEY = 'ulisses_theme';

/** Tema efetivo: preferencia salva ou, na ausencia, a do sistema operacional. */
export function resolveTheme() {
  const saved = localStorage.getItem(KEY);
  if (saved === 'light' || saved === 'dark') {
    return saved;
  }
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

export function applyTheme(theme) {
  document.documentElement.dataset.theme = theme;
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) {
    meta.setAttribute('content', theme === 'dark' ? '#0f0d17' : '#f7f5fb');
  }
}

/** Hook do alternador de tema, sincronizado com o <html data-theme> e o localStorage. */
export function useTheme() {
  const [theme, setTheme] = useState(resolveTheme);

  useEffect(() => {
    applyTheme(theme);
  }, [theme]);

  const toggle = () =>
    setTheme((current) => {
      const next = current === 'dark' ? 'light' : 'dark';
      localStorage.setItem(KEY, next);
      return next;
    });

  return { theme, toggle };
}
