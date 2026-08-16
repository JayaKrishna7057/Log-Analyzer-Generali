const STORAGE_KEY = 'log-analyzer:theme';
const DARK = 'dark';
const LIGHT = 'light';

/** The user's saved choice, or null if they have never chosen — falls back to the OS preference. */
export function getStoredTheme() {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored === DARK || stored === LIGHT ? stored : null;
  } catch {
    // Storage can be unavailable (private browsing, disabled by policy); fall back silently.
    return null;
  }
}

function prefersDark() {
  return typeof window !== 'undefined'
      && typeof window.matchMedia === 'function'
      && window.matchMedia('(prefers-color-scheme: dark)').matches;
}

/** The theme to render: the stored choice if there is one, else the OS preference. */
export function resolveInitialTheme() {
  return getStoredTheme() ?? (prefersDark() ? DARK : LIGHT);
}

/** Applies the theme to the document and remembers the choice for next time. */
export function applyTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme);
  try {
    localStorage.setItem(STORAGE_KEY, theme);
  } catch {
    // Non-fatal: the theme still applies for this session, it just won't persist.
  }
}

export function toggleTheme(current) {
  const next = current === DARK ? LIGHT : DARK;
  applyTheme(next);
  return next;
}

export { DARK, LIGHT };
