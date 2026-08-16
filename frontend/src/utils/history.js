const STORAGE_KEY = 'log-analyzer:history';

/** Newest analyses are kept; older ones are dropped once the list passes this length. */
const MAX_ENTRIES = 20;

function readAll() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    // Corrupted or unavailable storage reads as empty rather than throwing into the render path.
    return [];
  }
}

function writeAll(entries) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(entries));
    return true;
  } catch {
    return false;
  }
}

function uid() {
  return typeof crypto !== 'undefined' && crypto.randomUUID
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/** Every saved analysis, newest first. */
export function listHistory() {
  return readAll().sort((a, b) => b.savedAt - a.savedAt);
}

/**
 * Saves a completed analysis for later review without re-uploading.
 *
 * A run whose payload does not fit even after evicting every older entry (a very large record
 * list) is simply not saved — the analysis just shown is unaffected either way, since it already
 * lives in the app's own React state.
 *
 * @param {object} koReport the full KoReportDto as returned by /api/analyze
 * @param {{stdoutName: string|null, stderrName: string|null}} fileNames
 * @returns {boolean} whether the entry was actually persisted
 */
export function saveToHistory(koReport, fileNames) {
  if (!koReport?.analysis) return false;

  const entry = {
    id: uid(),
    savedAt: Date.now(),
    jobName: koReport.analysis.jobName || null,
    overallStatus: koReport.analysis.overallStatus || null,
    detectedFormat: koReport.analysis.detectedFormat || null,
    formatConfidence: koReport.analysis.formatConfidence ?? null,
    totalError: koReport.analysis.totalError ?? null,
    totalWarning: koReport.analysis.totalWarning ?? null,
    totalOk: koReport.analysis.totalOk ?? null,
    totalKo: koReport.analysis.totalKo ?? null,
    stdoutName: fileNames?.stdoutName ?? null,
    stderrName: fileNames?.stderrName ?? null,
    koReport,
  };

  let entries = [entry, ...readAll()];
  entries = entries.slice(0, MAX_ENTRIES);

  // Storage quotas are per-origin and shared with everything else on it. If the browser refuses
  // the write, keep evicting the oldest entries — the ones least likely to still matter — until
  // it fits or there is nothing left to drop.
  while (entries.length > 0 && !writeAll(entries)) {
    entries.pop();
  }

  return entries.some((e) => e.id === entry.id);
}

export function deleteHistoryEntry(id) {
  writeAll(readAll().filter((e) => e.id !== id));
}

export function clearHistory() {
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    // Nothing to do if storage is unavailable — there is then nothing stored to clear.
  }
}
