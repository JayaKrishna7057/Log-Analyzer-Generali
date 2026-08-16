import { describe, it, expect, beforeEach } from 'vitest';
import { clearHistory, deleteHistoryEntry, listHistory, saveToHistory } from './history';

function koReport(overrides = {}) {
  return {
    analysis: {
      jobName: 'NIGHTLY_LOAD',
      overallStatus: 'SUCCESS',
      detectedFormat: 'batch-layer',
      formatConfidence: 1,
      totalOk: 10,
      totalKo: 0,
      totalError: 0,
      totalWarning: 0,
      ...overrides,
    },
    records: [],
    hasRecordLevelData: false,
  };
}

describe('history', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('starts empty', () => {
    expect(listHistory()).toEqual([]);
  });

  it('saves a completed analysis and lists it back', () => {
    const ok = saveToHistory(koReport(), { stdoutName: 'a_stdout.txt', stderrName: null });

    expect(ok).toBe(true);
    const entries = listHistory();
    expect(entries).toHaveLength(1);
    expect(entries[0].jobName).toBe('NIGHTLY_LOAD');
    expect(entries[0].stdoutName).toBe('a_stdout.txt');
    // The full response is kept so a saved entry can be reopened without re-analysing.
    expect(entries[0].koReport.analysis.jobName).toBe('NIGHTLY_LOAD');
  });

  it('does not save a payload with no analysis', () => {
    expect(saveToHistory({ records: [] }, {})).toBe(false);
    expect(saveToHistory(null, {})).toBe(false);
    expect(listHistory()).toEqual([]);
  });

  it('lists newest first', () => {
    saveToHistory(koReport({ jobName: 'FIRST' }), {});
    saveToHistory(koReport({ jobName: 'SECOND' }), {});

    const entries = listHistory();
    expect(entries[0].jobName).toBe('SECOND');
    expect(entries[1].jobName).toBe('FIRST');
  });

  it('caps the list rather than growing without bound', () => {
    for (let i = 0; i < 25; i++) {
      saveToHistory(koReport({ jobName: `RUN_${i}` }), {});
    }

    // 20 is the documented cap; the newest run must be the one kept, not dropped.
    expect(listHistory().length).toBeLessThanOrEqual(20);
    expect(listHistory()[0].jobName).toBe('RUN_24');
  });

  it('deletes a single entry by id, leaving the others', () => {
    saveToHistory(koReport({ jobName: 'KEEP' }), {});
    saveToHistory(koReport({ jobName: 'REMOVE' }), {});

    const toRemove = listHistory().find((e) => e.jobName === 'REMOVE');
    deleteHistoryEntry(toRemove.id);

    const remaining = listHistory();
    expect(remaining).toHaveLength(1);
    expect(remaining[0].jobName).toBe('KEEP');
  });

  it('clears everything', () => {
    saveToHistory(koReport(), {});
    clearHistory();
    expect(listHistory()).toEqual([]);
  });

  it('survives corrupted storage instead of throwing', () => {
    localStorage.setItem('log-analyzer:history', 'not json');
    expect(() => listHistory()).not.toThrow();
    expect(listHistory()).toEqual([]);
  });
});
