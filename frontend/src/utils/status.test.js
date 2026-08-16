import { describe, it, expect } from 'vitest';
import { statusClass, categoryLabel } from './status';

/**
 * These rules mirror the backend's status vocabulary. They previously lived in two components
 * and had drifted, so the cases below pin the tokens the backend actually emits.
 */
describe('statusClass', () => {

  it.each([
    'OK', 'SUCCESS', 'SUCCEEDED', 'COMPLETED', 'FINISHED_OK',
  ])('treats %s as a success', (status) => {
    expect(statusClass(status)).toBe('status-success');
  });

  it('normalises punctuation and case, so "STATUS : ok" reads as a success', () => {
    expect(statusClass('ok')).toBe('status-success');
    expect(statusClass(' OK ')).toBe('status-success');
  });

  it('reports a success that mentions warnings as a warning, not a clean run', () => {
    expect(statusClass('FINISHED_OK_WARNINGS')).toBe('status-warning');
    // Emitted by BatchLayerProfile's OK_ prefix convention. This is the case that used to render
    // as a success in one view and as unknown in the other.
    expect(statusClass('OK_WITH_WARNINGS')).toBe('status-warning');
  });

  it('keeps a plain OK_ status in the success family', () => {
    expect(statusClass('OK_LOADED')).toBe('status-success');
  });

  it.each(['FAILED', 'FAILURE', 'KO', 'ERROR'])('treats %s as a failure', (status) => {
    expect(statusClass(status)).toBe('status-failed');
  });

  it.each(['RUNNING', 'IN PROGRESS'])('treats %s as running', (status) => {
    expect(statusClass(status)).toBe('status-running');
  });

  it('does not fail a status merely for containing "OK" as a substring', () => {
    // The old rule classified anything containing OK as a failure.
    expect(statusClass('LOOKUP_DONE')).not.toBe('status-failed');
    expect(statusClass('TOKEN_REFRESHED')).not.toBe('status-failed');
  });

  it('reports an unrecognised or missing status as unknown rather than guessing', () => {
    expect(statusClass('WAT')).toBe('status-unknown');
    expect(statusClass('')).toBe('status-unknown');
    expect(statusClass(null)).toBe('status-unknown');
    expect(statusClass(undefined)).toBe('status-unknown');
  });
});

describe('categoryLabel', () => {

  it('maps known backend categories to readable names', () => {
    expect(categoryLabel('DATA_QUALITY')).toBe('Data quality');
    expect(categoryLabel('UNKNOWN')).toBe('Unclassified');
  });

  it('falls back to the raw value so a new backend category still renders', () => {
    expect(categoryLabel('SOME_FUTURE_CATEGORY')).toBe('SOME_FUTURE_CATEGORY');
  });
});
