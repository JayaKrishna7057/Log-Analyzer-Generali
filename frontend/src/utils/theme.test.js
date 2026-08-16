import { describe, it, expect, beforeEach } from 'vitest';
import { applyTheme, getStoredTheme, resolveInitialTheme, toggleTheme, DARK, LIGHT } from './theme';

describe('theme', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
  });

  it('has no stored preference before the user picks one', () => {
    expect(getStoredTheme()).toBeNull();
  });

  it('applies the theme to the document and remembers it', () => {
    applyTheme(LIGHT);

    expect(document.documentElement.getAttribute('data-theme')).toBe(LIGHT);
    expect(getStoredTheme()).toBe(LIGHT);
  });

  it('resolves to the stored choice once one has been made', () => {
    applyTheme(DARK);
    expect(resolveInitialTheme()).toBe(DARK);

    applyTheme(LIGHT);
    expect(resolveInitialTheme()).toBe(LIGHT);
  });

  it('toggle flips the theme and returns the new value', () => {
    expect(toggleTheme(DARK)).toBe(LIGHT);
    expect(document.documentElement.getAttribute('data-theme')).toBe(LIGHT);

    expect(toggleTheme(LIGHT)).toBe(DARK);
    expect(document.documentElement.getAttribute('data-theme')).toBe(DARK);
  });

  it('ignores a corrupted stored value rather than crashing', () => {
    localStorage.setItem('log-analyzer:theme', 'not-a-real-theme');
    expect(getStoredTheme()).toBeNull();
  });
});
