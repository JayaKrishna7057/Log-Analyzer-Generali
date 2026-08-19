import { Fragment, useEffect, useState } from 'react';
import { statusClass, statusWords, categoryLabel, PAGE_SIZE } from '../utils/status';
import { hasExtraMetrics, formatExtraMetrics } from '../utils/metrics';
import { downloadKoReportCsv, downloadLayerErrorDetailsCsv } from '../utils/csv';
import { analyzeLayerDetail } from '../api/logAnalyzerApi';

/* ── Status badges ───────────────────────────────────────────────── */

function StatusBadge({ status }) {
  return (
    <span className={`status-badge ${statusClass(status)}`}>
      {/* A plain inline span, not a flex item's direct text - <wbr> inside a flex container
          gets treated as its own flex item and wraps character-by-character instead of at the
          word boundary it marks. Wrapping it in its own box sidesteps that entirely. */}
      <span className="status-badge-text">
        {statusWords(status).map((word, i) => (
          <Fragment key={i}>
            {i > 0 && <>_<wbr /></>}
            {word}
          </Fragment>
        ))}
      </span>
    </span>
  );
}

/**
 * A record's outcome. A null status means the log declared the record but never reported on it,
 * which is said outright rather than left as an empty cell.
 */
function RecordBadge({ status }) {
  if (!status) {
    return <span className="rec-badge rec-none" title="No status line for this record">NOT REPORTED</span>;
  }
  if (status === 'OK') return <span className="rec-badge rec-ok">OK</span>;
  if (status === 'KO') return <span className="rec-badge rec-ko">KO</span>;
  return <span>{status}</span>;
}

/* ── Summary tab ─────────────────────────────────────────────────── */

function SummaryTab({ analysis }) {
  const a = analysis;

  const layers = [...(a.completedEtlLayers || []), ...(a.inProgressEtlLayers || [])];
  const showExtras = hasExtraMetrics(layers);

  const infoRows = [
    ['Job',       a.jobName],
    ['Job ID',    a.jobId],
    ['DAG Name',  a.dagName],
    ['Task Name', a.taskName],
    ['Format',    a.detectedFormat
        ? `${a.detectedFormat} (${Math.round((a.formatConfidence ?? 0) * 100)}% confidence)`
        : null],
  ].filter(([, v]) => v);

  const metricRows = [
    ['Start',    a.overallStartTime],
    ['End',      a.overallEndTime],
    ['Duration', a.overallDuration],
    ['Total Records to Process', a.totalRawData || null],
    ['OK',       a.totalOk   || null],
    ['KO / Discarded', a.totalKo || null],
    ['Errors (log lines)',   a.totalError   || null],
    ['Warnings (log lines)', a.totalWarning || null],
  ].filter(([, v]) => v != null && v !== 'N/A');

  return (
    <div>
      <div className="ko-summary-status-row">
        <StatusBadge status={a.overallStatus} />
        {a.detectedFormat && a.detectedFormat !== 'generic' && (
          <span className="format-chip">
            {`format: ${a.detectedFormat} · ${Math.round((a.formatConfidence ?? 0) * 100)}%`}
          </span>
        )}
        {a.detectedFormat === 'generic' && (
          <span className="format-chip format-chip-generic">format: not recognised</span>
        )}
      </div>

      <div className="ko-summary-grid">
        <div className="ko-summary-section">
          <h4 className="ko-summary-section-title">Job Information</h4>
          <div className="summary-kv">
            {infoRows.length > 0
              ? infoRows.map(([label, value]) => (
                  <div key={label} className="kv-row">
                    <span className="kv-key">{label}</span>
                    <span className="kv-val">{value}</span>
                  </div>
                ))
              : <p className="text-muted">No job metadata extracted.</p>
            }
          </div>
        </div>

        <div className="ko-summary-section">
          <h4 className="ko-summary-section-title">Execution &amp; Counts</h4>
          <div className="summary-kv">
            {metricRows.length > 0
              ? metricRows.map(([label, value]) => (
                  <div key={label} className="kv-row">
                    <span className="kv-key">{label}</span>
                    <span className="kv-val">{String(value)}</span>
                  </div>
                ))
              : <p className="text-muted">No execution metrics extracted.</p>
            }
          </div>
        </div>
      </div>

      {layers.length > 0 && (
        <div className="ko-summary-section" style={{ marginTop: '24px' }}>
          <h4 className="ko-summary-section-title">ETL Layers</h4>
          <div className="table-wrapper">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Layer</th>
                  <th>Start</th>
                  <th>End</th>
                  <th>Duration</th>
                  <th>Raw</th>
                  <th>OK</th>
                  <th>KO</th>
                  <th>Error</th>
                  <th>Warning</th>
                  <th>Status</th>
                  {showExtras && <th>Also reported</th>}
                </tr>
              </thead>
              <tbody>
                {layers.map((l, i) => (
                  <tr key={i}>
                    <td>{l.layerName}</td>
                    <td className="nowrap-cell">{l.startTime ?? 'N/A'}</td>
                    <td className="nowrap-cell">{l.endTime ?? 'N/A'}</td>
                    <td className="nowrap-cell">{l.duration ?? 'N/A'}</td>
                    <td className="num-cell">{l.rawData ?? 'N/A'}</td>
                    <td className="num-cell">{l.okCount ?? 'N/A'}</td>
                    <td className="num-cell">{l.koCount ?? 'N/A'}</td>
                    <td className="num-cell">{l.errorCount ?? 'N/A'}</td>
                    <td className="num-cell">{l.warningCount ?? 'N/A'}</td>
                    <td className="status-cell"><StatusBadge status={l.status} /></td>
                    {showExtras && <td>{formatExtraMetrics(l) ?? 'N/A'}</td>}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}

/* ── Records tab (all records) ───────────────────────────────────── */

/** Filter value for records the log declared but never reported a status for. */
const NOT_REPORTED = '__NOT_REPORTED__';

function RecordsTab({ records }) {
  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState('');
  const [shown,  setShown]  = useState(PAGE_SIZE);

  const q  = search.trim().toLowerCase();
  const ok = records.filter(r => r.status === 'OK').length;
  const ko = records.filter(r => r.status === 'KO').length;
  // Declared by the log but never given a status line.
  const unreported = records.filter(r => !r.status).length;

  const visible = records.filter(r => {
    if (filter === NOT_REPORTED) {
      if (r.status) return false;
    } else if (filter && r.status !== filter) {
      return false;
    }
    if (q) {
      return (r.dialogueId  || '').toLowerCase().includes(q)
          || (r.internalKey || '').toLowerCase().includes(q)
          || (r.uniqueCode  || '').toLowerCase().includes(q)
          || (r.status || 'not reported').toLowerCase().includes(q)
          || (r.koReason    || '').toLowerCase().includes(q);
    }
    return true;
  });

  const page = visible.slice(0, shown);

  return (
    <div>
      <div className="ko-stats-row">
        <span className="ko-stat-pill"><RecordBadge status="OK" /><span className="ko-stat-n">{ok}</span></span>
        <span className="ko-stat-pill"><RecordBadge status="KO" /><span className="ko-stat-n">{ko}</span></span>
        {unreported > 0 && (
          <span className="ko-stat-pill">
            <RecordBadge status={null} /><span className="ko-stat-n">{unreported}</span>
          </span>
        )}
        <span className="ko-stat-pill" style={{ color: 'var(--text-secondary)' }}>
          Total <span className="ko-stat-n">{records.length}</span>
        </span>
      </div>

      <div className="filter-bar">
        <input
          className="search-input"
          placeholder={`Search ${records.length} records…`}
          value={search}
          onChange={e => { setSearch(e.target.value); setShown(PAGE_SIZE); }}
        />
        <select
          className="select-input"
          value={filter}
          onChange={e => { setFilter(e.target.value); setShown(PAGE_SIZE); }}
        >
          <option value="">All statuses</option>
          {unreported > 0 && <option value={NOT_REPORTED}>Not reported only</option>}
          <option value="OK">OK only</option>
          <option value="KO">KO only</option>
        </select>
        <span className="filter-count">{visible.length} / {records.length}</span>
      </div>

      <div className="table-wrapper">
        <table className="data-table">
          <thead>
            <tr>
              <th>Dialogue ID</th>
              <th>Internal Key</th>
              <th>Unique Code</th>
              <th>Status</th>
              <th>KO Reason</th>
            </tr>
          </thead>
          <tbody>
            {page.map((r, i) => (
              <tr key={i}>
                <td className="mono-cell">{r.dialogueId}</td>
                <td>{r.internalKey !== '-' ? r.internalKey : <span className="text-muted">—</span>}</td>
                <td>{r.uniqueCode}</td>
                <td><RecordBadge status={r.status} /></td>
                <td className="ko-reason-cell">{r.koReason || ''}</td>
              </tr>
            ))}
            {!page.length && (
              <tr>
                <td colSpan={5} className="empty-row">No matching records.</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {shown < visible.length && (
        <div className="pager">
          <button className="issues-ctrl-btn" onClick={() => setShown(s => s + PAGE_SIZE)}>
            Show {Math.min(PAGE_SIZE, visible.length - shown)} more
          </button>
          <span className="pager-info">Showing {page.length} of {visible.length}</span>
        </div>
      )}
    </div>
  );
}

/* ── KO Details tab ──────────────────────────────────────────────── */

function KoDetailsTab({ records }) {
  const koRecords = records.filter(r => r.status === 'KO');
  const [search, setSearch] = useState('');
  const [shown,  setShown]  = useState(PAGE_SIZE);

  const q = search.trim().toLowerCase();
  const visible = q
    ? koRecords.filter(r =>
        (r.dialogueId  || '').toLowerCase().includes(q)
        || (r.uniqueCode  || '').toLowerCase().includes(q)
        || (r.internalKey || '').toLowerCase().includes(q)
        || (r.koReason    || '').toLowerCase().includes(q))
    : koRecords;

  const page = visible.slice(0, shown);

  const reasonCounts = {};
  koRecords.forEach(r => {
    const key = r.koReason || '(unknown reason)';
    reasonCounts[key] = (reasonCounts[key] || 0) + 1;
  });
  const reasonSummary = Object.entries(reasonCounts).sort(([, a], [, b]) => b - a);

  return (
    <div>
      <div className="ko-reason-summary">
        <h4 className="ko-summary-section-title">
          Rejection reason breakdown ({koRecords.length} KO records)
        </h4>
        <div className="table-wrapper">
          <table className="data-table">
            <thead>
              <tr><th>Rejection Reason</th><th style={{ width: '80px' }}>Count</th></tr>
            </thead>
            <tbody>
              {reasonSummary.map(([reason, count]) => (
                <tr key={reason}>
                  <td className="ko-reason-cell">{reason}</td>
                  <td className="num-cell">{count}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <h4 className="ko-summary-section-title" style={{ marginTop: '24px' }}>
        KO record detail
      </h4>
      <div className="filter-bar">
        <input
          className="search-input"
          placeholder={`Search ${koRecords.length} KO records…`}
          value={search}
          onChange={e => { setSearch(e.target.value); setShown(PAGE_SIZE); }}
        />
        <span className="filter-count">{visible.length} / {koRecords.length}</span>
      </div>

      <div className="table-wrapper">
        <table className="data-table">
          <thead>
            <tr>
              <th>Dialogue ID</th>
              <th>Internal Key</th>
              <th>Unique Code</th>
              <th>Rejection Reason</th>
            </tr>
          </thead>
          <tbody>
            {page.map((r, i) => (
              <tr key={i}>
                <td className="mono-cell">{r.dialogueId}</td>
                <td>{r.internalKey !== '-' ? r.internalKey : <span className="text-muted">—</span>}</td>
                <td>{r.uniqueCode}</td>
                <td className="ko-reason-cell">
                  {r.koReason || <span className="text-muted">(no reason extracted)</span>}
                </td>
              </tr>
            ))}
            {!page.length && (
              <tr>
                <td colSpan={4} className="empty-row">No matching KO records.</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {shown < visible.length && (
        <div className="pager">
          <button className="issues-ctrl-btn" onClick={() => setShown(s => s + PAGE_SIZE)}>
            Show {Math.min(PAGE_SIZE, visible.length - shown)} more
          </button>
          <span className="pager-info">Showing {page.length} of {visible.length}</span>
        </div>
      )}
    </div>
  );
}

/* ── Issues tab ──────────────────────────────────────────────────── */

function IssuesTab({ issues }) {
  const [search,    setSearch]    = useState('');
  const [sevFilter, setSevFilter] = useState('ALL');
  const [shown,     setShown]     = useState(PAGE_SIZE);

  const errors   = issues.filter(i => i.severity === 'ERROR');
  const warnings = issues.filter(i => i.severity === 'WARNING');

  const q = search.trim().toLowerCase();
  const visible = issues.filter(i => {
    if (sevFilter !== 'ALL' && i.severity !== sevFilter) return false;
    if (q) {
      return i.message.toLowerCase().includes(q)
          || categoryLabel(i.category).toLowerCase().includes(q)
          || (i.component || '').toLowerCase().includes(q);
    }
    return true;
  });

  const page = visible.slice(0, shown);

  return (
    <div>
      <div className="ko-stats-row">
        {errors.length > 0 && (
          <span className="issues-pill issues-pill--error">
            ✕ {errors.length} error{errors.length !== 1 ? 's' : ''}{' '}
            ({errors.reduce((s, i) => s + i.occurrences, 0)} occurrences)
          </span>
        )}
        {warnings.length > 0 && (
          <span className="issues-pill issues-pill--warn">
            ⚠ {warnings.length} warning{warnings.length !== 1 ? 's' : ''}
          </span>
        )}
      </div>

      <div className="filter-bar">
        <input
          className="search-input"
          placeholder="Search messages, categories…"
          value={search}
          onChange={e => { setSearch(e.target.value); setShown(PAGE_SIZE); }}
        />
        <div className="sev-pill-group">
          {[['ALL','All'], ['ERROR', `Errors (${errors.length})`], ['WARNING', `Warnings (${warnings.length})`]].map(([v, l]) => (
            <button
              key={v}
              className={`sev-pill${sevFilter === v ? ' sev-pill--active' : ''}${v !== 'ALL' ? ` sev-pill--${v.toLowerCase()}` : ''}`}
              onClick={() => setSevFilter(v)}
            >
              {l}
            </button>
          ))}
        </div>
        <span className="filter-count">{visible.length} / {issues.length}</span>
      </div>

      <div className="table-wrapper">
        <table className="data-table">
          <thead>
            <tr>
              <th>Severity</th>
              <th>Category</th>
              <th>Occurrences</th>
              <th>Location</th>
              <th>Message</th>
            </tr>
          </thead>
          <tbody>
            {page.map((issue, i) => (
              <tr key={i}>
                <td>
                  <span className={`severity-tag severity-${issue.severity.toLowerCase()}`}>
                    {issue.severity}
                  </span>
                </td>
                <td>{categoryLabel(issue.category)}</td>
                <td className="num-cell">{issue.occurrences}</td>
                <td>
                  <span className="source-ref">{issue.source}:{issue.firstLine}</span>
                </td>
                <td className="ko-reason-cell">{issue.message}</td>
              </tr>
            ))}
            {!page.length && (
              <tr>
                <td colSpan={5} className="empty-row">No matching issues.</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {shown < visible.length && (
        <div className="pager">
          <button className="issues-ctrl-btn" onClick={() => setShown(s => s + PAGE_SIZE)}>
            Show {Math.min(PAGE_SIZE, visible.length - shown)} more
          </button>
          <span className="pager-info">Showing {page.length} of {visible.length}</span>
        </div>
      )}
    </div>
  );
}

/* ── ETL Layer Errors tab ────────────────────────────────────────── */

/** One row per (record, error) pair - warnings are left out for now, so a record's warning line
 *  (e.g. a missing optional table) doesn't crowd out the actual failure reason next to it. */
function flattenDetailRecords(detail) {
  const rows = [];
  (detail?.records || []).forEach((r) => {
    (r.issues || [])
      .filter((issue) => issue.severity === 'ERROR')
      .forEach((issue) => {
        rows.push({
          timestamp: r.timestamp,
          recordKey: r.recordKey,
          recordId: r.recordId,
          severity: issue.severity,
          code: issue.code,
          message: issue.message,
        });
      });
  });
  return rows;
}

function LayerDetailTable({ detail }) {
  const [search, setSearch] = useState('');
  const [shown,  setShown]  = useState(PAGE_SIZE);

  const allRows = flattenDetailRecords(detail);
  const q = search.trim().toLowerCase();
  const visible = q
    ? allRows.filter(r =>
        (r.recordKey || '').toLowerCase().includes(q)
        || (r.recordId || '').toLowerCase().includes(q)
        || (r.code || '').toLowerCase().includes(q)
        || (r.message || '').toLowerCase().includes(q))
    : allRows;

  const page = visible.slice(0, shown);

  return (
    <div className="layer-detail-panel">
      <div className="filter-bar">
        <input
          className="search-input"
          placeholder={`Search ${allRows.length} records…`}
          value={search}
          onChange={e => { setSearch(e.target.value); setShown(PAGE_SIZE); }}
        />
        <span className="filter-count">{visible.length} / {allRows.length}</span>
      </div>
      <div className="table-wrapper">
        <table className="data-table">
          <thead>
            <tr>
              <th>Timestamp</th>
              <th>Field</th>
              <th>ID</th>
              <th>Severity</th>
              <th>Code</th>
              <th>Message</th>
            </tr>
          </thead>
          <tbody>
            {page.map((r, i) => (
              <tr key={i}>
                <td className="nowrap-cell">{r.timestamp ?? 'N/A'}</td>
                <td>{r.recordKey}</td>
                <td className="mono-cell">{r.recordId}</td>
                <td>
                  {r.severity
                    ? <span className={`severity-tag severity-${r.severity.toLowerCase()}`}>{r.severity}</span>
                    : <span className="text-muted">—</span>}
                </td>
                <td>{r.code ?? <span className="text-muted">—</span>}</td>
                <td className="ko-reason-cell">{r.message ?? ''}</td>
              </tr>
            ))}
            {!page.length && (
              <tr>
                <td colSpan={6} className="empty-row">No matching records.</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {shown < visible.length && (
        <div className="pager">
          <button className="issues-ctrl-btn" onClick={() => setShown(s => s + PAGE_SIZE)}>
            Show {Math.min(PAGE_SIZE, visible.length - shown)} more
          </button>
          <span className="pager-info">Showing {page.length} of {visible.length}</span>
        </div>
      )}
    </div>
  );
}

/** A row's attach control: a hidden file input plus a busy/error state for its own upload. */
function AttachDetailButton({ onFile, disabled }) {
  const [busy,  setBusy]  = useState(false);
  const [error, setError] = useState(null);

  const handleChange = async (e) => {
    const file = e.target.files?.[0];
    e.target.value = ''; // allow re-selecting the same file after an error
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      await onFile(file);
    } catch (err) {
      setError(err.message || 'Failed to analyze the file.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <span className="attach-detail-wrap">
      <label
        className={`issues-ctrl-btn attach-detail-btn${disabled || busy ? ' attach-detail-btn--busy' : ''}`}
        title="Attach detail file"
      >
        {busy ? 'Analyzing…' : 'Attach file'}
        <input type="file" accept=".txt,.log,.out" onChange={handleChange} disabled={disabled || busy} hidden />
      </label>
      {error && <span className="attach-detail-error" title={error}>⚠️ {error}</span>}
    </span>
  );
}

function ErrorLayersTab({ layers, details, onAttach, jobName }) {
  const [search, setSearch] = useState('');
  const [shown,  setShown]  = useState(PAGE_SIZE);
  const [expanded, setExpanded] = useState(() => new Set());

  const attachedCount = Object.keys(details || {}).length;

  const toggleExpanded = (executionId) => {
    setExpanded(prev => {
      const next = new Set(prev);
      next.has(executionId) ? next.delete(executionId) : next.add(executionId);
      return next;
    });
  };

  // Worst offenders first, so a run with dozens of layers still leads with what matters.
  const sorted = [...layers].sort((a, b) => (b.errorCount ?? 0) - (a.errorCount ?? 0));

  const q = search.trim().toLowerCase();
  const visible = q
    ? sorted.filter(l => (l.layerName || '').toLowerCase().includes(q)
        || (l.status || '').toLowerCase().includes(q))
    : sorted;

  const page = visible.slice(0, shown);

  const totalErrors = layers.reduce((s, l) => s + (l.errorCount ?? 0), 0);
  const totalRaw    = layers.reduce((s, l) => s + (l.rawData ?? 0), 0);

  const errorRate = (l) => (l.rawData ? ((l.errorCount ?? 0) / l.rawData * 100).toFixed(1) + '%' : 'N/A');

  return (
    <div>
      <div className="ko-stats-row">
        <span className="issues-pill issues-pill--error">
          ✕ {layers.length} layer{layers.length !== 1 ? 's' : ''} with errors
        </span>
        <span className="ko-stat-pill" style={{ color: 'var(--text-secondary)' }}>
          Total errors <span className="ko-stat-n">{totalErrors}</span>
        </span>
        {totalRaw > 0 && (
          <span className="ko-stat-pill" style={{ color: 'var(--text-secondary)' }}>
            Overall error rate <span className="ko-stat-n">{(totalErrors / totalRaw * 100).toFixed(1)}%</span>
          </span>
        )}
        {attachedCount > 0 && (
          <button
            type="button"
            className="download-csv-btn"
            style={{ marginLeft: 'auto' }}
            onClick={() => downloadLayerErrorDetailsCsv(jobName, details)}
            title={`Download error records from ${attachedCount} attached layer${attachedCount !== 1 ? 's' : ''}`}
          >
            <span aria-hidden="true">⬇️</span> Download Record Detail CSV
          </button>
        )}
      </div>

      <div className="filter-bar">
        <input
          className="search-input"
          placeholder={`Search ${layers.length} layers…`}
          value={search}
          onChange={e => { setSearch(e.target.value); setShown(PAGE_SIZE); }}
        />
        <span className="filter-count">{visible.length} / {layers.length}</span>
      </div>

      <div className="table-wrapper">
        <table className="data-table">
          <thead>
            <tr>
              <th>Layer</th>
              <th>Execution ID</th>
              <th>Raw</th>
              <th>OK</th>
              <th>Error</th>
              <th>Error Rate</th>
              <th>Warning</th>
              <th>KO</th>
              <th>Status</th>
              <th>Duration</th>
              <th>Record Detail</th>
            </tr>
          </thead>
          <tbody>
            {page.map((l) => {
              const executionId = l.executionId;
              const detail = executionId ? details?.[executionId] : null;
              const isExpanded = executionId && expanded.has(executionId);
              // Layer name + execution id, not array position - an index key let a row's attach
              // error/busy state survive onto a *different* layer once search filtering changed
              // which layer occupied that index.
              const rowKey = `${l.layerName}::${executionId ?? 'N/A'}`;

              return (
                <Fragment key={rowKey}>
                  <tr>
                    <td>{l.layerName}</td>
                    <td className="mono-cell">{executionId ?? 'N/A'}</td>
                    <td className="num-cell">{l.rawData ?? 'N/A'}</td>
                    <td className="num-cell">{l.okCount ?? 'N/A'}</td>
                    <td className="num-cell">{l.errorCount ?? 'N/A'}</td>
                    <td className="num-cell">{errorRate(l)}</td>
                    <td className="num-cell">{l.warningCount ?? 'N/A'}</td>
                    <td className="num-cell">{l.koCount ?? 'N/A'}</td>
                    <td className="status-cell"><StatusBadge status={l.status} /></td>
                    <td className="nowrap-cell">{l.duration ?? 'N/A'}</td>
                    <td className="record-detail-cell">
                      {!executionId || !onAttach ? (
                        <span className="text-muted">—</span>
                      ) : detail ? (
                        <button
                          type="button"
                          className="issues-ctrl-btn"
                          onClick={() => toggleExpanded(executionId)}
                        >
                          {isExpanded ? '▾' : '▸'} {detail.records.length} record{detail.records.length !== 1 ? 's' : ''}
                        </button>
                      ) : (
                        <AttachDetailButton
                          onFile={async (file) => {
                            const parsed = await analyzeLayerDetail(file);
                            // The file names its own execution - trust that over which row's
                            // button opened the picker, so attaching the wrong file (e.g. the
                            // OS dialog re-offering the last pick) is rejected instead of
                            // silently showing one layer's records under another's row. A file
                            // with no IDEXECUTION line at all is rejected too - it can't be
                            // verified against this row, so it isn't safe to trust by default.
                            if (parsed.executionId !== executionId) {
                              throw new Error(
                                parsed.executionId
                                  ? `This file belongs to "${parsed.layerName || 'another layer'}" `
                                    + `(execution ${parsed.executionId}), not "${l.layerName}" `
                                    + `(execution ${executionId}). Choose the detail file for this layer.`
                                  : `This file has no execution id ("IDEXECUTION") - it can't be `
                                    + `verified as belonging to "${l.layerName}" (execution ${executionId}).`
                              );
                            }
                            onAttach(executionId, parsed);
                          }}
                        />
                      )}
                    </td>
                  </tr>
                  {isExpanded && detail && (
                    <tr className="layer-detail-row">
                      <td colSpan={11}>
                        <LayerDetailTable detail={detail} />
                      </td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
            {!page.length && (
              <tr>
                <td colSpan={11} className="empty-row">No matching layers.</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {shown < visible.length && (
        <div className="pager">
          <button className="issues-ctrl-btn" onClick={() => setShown(s => s + PAGE_SIZE)}>
            Show {Math.min(PAGE_SIZE, visible.length - shown)} more
          </button>
          <span className="pager-info">Showing {page.length} of {visible.length}</span>
        </div>
      )}
    </div>
  );
}

/* ── Root component ──────────────────────────────────────────────── */

export default function KoReport({ report, onClear, onAttachLayerDetail }) {
  const [tab, setTab] = useState('summary');

  // Reset to summary tab whenever a genuinely new analysis arrives - keyed on `analysis` rather
  // than `report` because attaching a layer detail file replaces `report` in place (to add
  // layerErrorDetails) without changing the analysis itself, and that must not throw the user
  // back to Summary right after they attached something on another tab.
  useEffect(() => {
    if (report) setTab('summary');
  }, [report?.analysis ?? report]);

  if (!report) {
    return (
      <div className="ko-report">
        <div className="ko-upload-zone">
          <div className="ko-upload-icon" aria-hidden="true">📋</div>
          <p className="ko-upload-title">KO Report</p>
          <p className="ko-upload-hint">
            Upload log files in the <strong>Log Analyzer</strong> tab and click{' '}
            <strong>Analyze Logs</strong>. The KO Report is generated automatically
            as part of the same analysis — no separate upload needed.
          </p>
        </div>
      </div>
    );
  }

  // Tolerate an unexpected payload shape rather than crashing the whole view.
  // A backend serving an older contract returns a bare AnalysisReport instead of
  // the { analysis, records, hasRecordLevelData } wrapper.
  const analysis = report.analysis ?? report;
  const records  = Array.isArray(report.records) ? report.records : [];
  const hasRecordLevelData = report.hasRecordLevelData === true && records.length > 0;

  const koCount = records.filter(r => r.status === 'KO').length;
  const issues  = analysis.issues || [];

  const allLayers = [...(analysis.completedEtlLayers || []), ...(analysis.inProgressEtlLayers || [])];
  const errorLayers = allLayers.filter(l => (l.errorCount ?? 0) > 0);
  const layerErrorDetails = report.layerErrorDetails || {};

  const tabs = [
    { id: 'summary', label: 'Summary' },
    errorLayers.length > 0 && { id: 'error-layers', label: `ETL Layer Errors (${errorLayers.length})` },
    hasRecordLevelData && { id: 'records', label: `All Records (${records.length})` },
    hasRecordLevelData && koCount > 0 && { id: 'ko', label: `KO Details (${koCount})` },
    issues.length > 0 && { id: 'issues', label: `Issues (${issues.length})` },
  ].filter(Boolean);

  const jobLabel = analysis.jobName || analysis.jobId || 'KO Report';

  const handleDownload = () => {
    downloadKoReportCsv({ analysis, records, hasRecordLevelData });
  };

  return (
    <div className="ko-report">
      <div className="ko-report-header">
        <div>
          <h2 className="ko-report-title">{jobLabel}</h2>
          <p className="ko-report-subtitle">Generated from log analysis</p>
        </div>
        <div className="ko-report-header-actions">
          <button type="button" className="download-csv-btn" onClick={handleDownload}>
            <span aria-hidden="true">⬇️</span> Download CSV
          </button>
          {onClear && (
            <button type="button" className="ko-clear-btn" onClick={onClear}>
              Clear
            </button>
          )}
        </div>
      </div>

      <div className="tab-bar" role="tablist" aria-label="KO report sections">
        {tabs.map(t => (
          <button
            key={t.id}
            role="tab"
            aria-selected={tab === t.id}
            className={`tab-btn${tab === t.id ? ' tab-btn--active' : ''}`}
            onClick={() => setTab(t.id)}
          >
            {t.label}
          </button>
        ))}
      </div>

      <div className="tab-content">
        {tab === 'summary' && <SummaryTab analysis={analysis} />}
        {tab === 'error-layers' && errorLayers.length > 0 && (
          <ErrorLayersTab
            layers={errorLayers}
            details={layerErrorDetails}
            onAttach={onAttachLayerDetail}
            jobName={analysis.jobName}
          />
        )}
        {tab === 'records' && hasRecordLevelData && <RecordsTab records={records} />}
        {tab === 'ko' && hasRecordLevelData && koCount > 0 && <KoDetailsTab records={records} />}
        {tab === 'issues' && issues.length > 0 && <IssuesTab issues={issues} />}
      </div>

      {!hasRecordLevelData && (
        <div className="notes-panel" style={{ marginTop: '16px' }}>
          <p>
            Per-record status data is not available for this log format
            ({analysis.detectedFormat || 'unknown format'}).
            The summary and issues sections are derived from the overall log analysis.
          </p>
        </div>
      )}
    </div>
  );
}
