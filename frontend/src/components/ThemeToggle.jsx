import { useState } from 'react';
import { DARK, resolveInitialTheme, toggleTheme } from '../utils/theme';

function ThemeToggle() {
  const [theme, setTheme] = useState(resolveInitialTheme);

  const handleClick = () => setTheme((current) => toggleTheme(current));

  const isDark = theme === DARK;

  return (
    <button
      type="button"
      className="theme-toggle-btn"
      onClick={handleClick}
      aria-label={`Switch to ${isDark ? 'light' : 'dark'} theme`}
      title={`Switch to ${isDark ? 'light' : 'dark'} theme`}
    >
      <span aria-hidden="true">{isDark ? '☀️' : '🌙'}</span>
    </button>
  );
}

export default ThemeToggle;
