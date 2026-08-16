import { useEffect, useState } from 'react';
import { clearHistory, deleteHistoryEntry, listHistory } from '../utils/history';
import { statusClass } from '../utils/status';

function formatSavedAt(ms) {
  return new Date(ms).toLocaleString(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  });
}

function StatusBadge({ status }) {
  return <span className={`status-badge ${statusClass(status)}`}>{status || 'N/A'}</span>;
}

function HistoryRow({ entry, onView, onDelete }) {
  const a = entry;
  const jobLabel = a.jobName || a.stdoutName || a.stderrName || 'Untitled run';

  return (
    <div className="history-row">
      <div className="history-row-main">
        <div className="history-row-title">
          <span className="history-job-name">{jobLabel}</span>
          <StatusBadge status={a.overallStatus} />
          {a.detectedFormat && (
            <span className="format-chip">
              {`format: ${a.detectedFormat}${
                a.formatConfidence != null ? ` · ${Math.round(a.formatConfidence * 100)}%` : ''
              }`}
            </span>
          )}
        </div>
        <div className="history-row-meta">
          <span>{formatSavedAt(a.savedAt)}</span>
          {(a.stdoutName || a.stderrName) && (
            <span className="history-files">
              {[a.stdoutName, a.stderrName].filter(Boolean).join(' · ')}
            </span>
          )}
        </div>
        <div className="history-row-stats">
          {a.totalOk != null && <span className="history-stat">OK {a.totalOk}</span>}
          {a.totalKo != null && <span className="history-stat">KO {a.totalKo}</span>}
          {a.totalError != null && <span className="history-stat">Errors {a.totalError}</span>}
          {a.totalWarning != null && <span className="history-stat">Warnings {a.totalWarning}</span>}
        </div>
      </div>
      <div className="history-row-actions">
        <button type="button" className="history-view-btn" onClick={() => onView(entry)}>
          View
        </button>
        <button type="button" className="history-delete-btn" onClick={() => onDelete(entry.id)}>
          Delete
        </button>
      </div>
    </div>
  );
}

/**
 * Past analyses, kept in the browser's own storage so a user can revisit a prior run without
 * re-uploading the files. Nothing here reaches the backend — it is a client-side convenience,
 * and it never substitutes for the analysis itself: every entry stores the exact response the
 * backend returned at the time.
 */
function History({ onViewEntry }) {
  const [entries, setEntries] = useState(() => listHistory());
  const [confirmingClear, setConfirmingClear] = useState(false);

  // Picks up runs saved while this tab was not mounted (e.g. an analysis finishing after
  // switching to this tab is not possible today, but re-reading on focus keeps this honest
  // if that ever changes, and costs nothing since localStorage reads are synchronous).
  useEffect(() => {
    setEntries(listHistory());
  }, []);

  const handleDelete = (id) => {
    deleteHistoryEntry(id);
    setEntries(listHistory());
  };

  const handleClearAll = () => {
    if (!confirmingClear) {
      setConfirmingClear(true);
      return;
    }
    clearHistory();
    setEntries([]);
    setConfirmingClear(false);
  };

  if (entries.length === 0) {
    return (
      <div className="history-empty">
        <div className="history-empty-icon" aria-hidden="true">🕓</div>
        <p className="history-empty-title">No saved analyses yet</p>
        <p className="history-empty-hint">
          Every analysis you run is saved here automatically, in this browser, so you can come
          back to it later without uploading the files again.
        </p>
      </div>
    );
  }

  return (
    <div className="history-panel">
      <div className="history-header">
        <h2 className="history-title">Analysis History</h2>
        <button
          type="button"
          className={`history-clear-btn${confirmingClear ? ' history-clear-btn--confirm' : ''}`}
          onClick={handleClearAll}
          onBlur={() => setConfirmingClear(false)}
        >
          {confirmingClear ? 'Click again to confirm' : 'Clear All'}
        </button>
      </div>
      <p className="history-subtitle">
        {entries.length} saved run{entries.length === 1 ? '' : 's'} · stored only in this browser
      </p>
      <div className="history-list">
        {entries.map((entry) => (
          <HistoryRow key={entry.id} entry={entry} onView={onViewEntry} onDelete={handleDelete} />
        ))}
      </div>
    </div>
  );
}

export default History;
