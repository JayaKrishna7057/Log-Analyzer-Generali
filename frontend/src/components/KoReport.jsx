import { useEffect, useState } from 'react';
import { statusClass, categoryLabel, PAGE_SIZE } from '../utils/status';
import { hasExtraMetrics, formatExtraMetrics } from '../utils/metrics';
import { downloadKoReportCsv } from '../utils/csv';

/* ── Status badges ───────────────────────────────────────────────── */

function StatusBadge({ status }) {
  return <span className={`status-badge ${statusClass(status)}`}>{status || 'N/A'}</span>;
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
                    <td><StatusBadge status={l.status} /></td>
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

/* ── Root component ──────────────────────────────────────────────── */

export default function KoReport({ report, onClear }) {
  const [tab, setTab] = useState('summary');

  // Reset to summary tab whenever a new report arrives.
  useEffect(() => {
    if (report) setTab('summary');
  }, [report]);

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

  const tabs = [
    { id: 'summary', label: 'Summary' },
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
