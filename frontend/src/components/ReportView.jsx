import { useEffect, useRef, useState } from 'react';
import { downloadReportCsv } from '../utils/csv';
import { statusClass, categoryLabel } from '../utils/status';
import { hasExtraMetrics, formatExtraMetrics } from '../utils/metrics';

const COPIED_FEEDBACK_MS = 1500;

const ICON = {
  success: '✓',
  failed: '✕',
  running: '⏳',
  unknown: '●',
  completed: '✅',
  warning: '⚠',
  puzzle: '🧩',
  clipboard: '📋',
  download: '⬇️',
};

function statusIcon(status) {
  switch (statusClass(status)) {
    case 'status-success': return ICON.success;
    case 'status-warning': return ICON.warning;
    case 'status-failed':  return ICON.failed;
    case 'status-running': return ICON.running;
    default: return ICON.unknown;
  }
}

function StatusBadge({ status }) {
  return (
    <span className={`status-badge ${statusClass(status)}`}>
      <span aria-hidden="true">{statusIcon(status)}</span>
      {status || 'N/A'}
    </span>
  );
}

// ── Summary grid ────────────────────────────────────────────────────────────

function SummaryGrid({ report }) {
  const items = [
    ['📄', 'Job Name',         report.jobName,         ''],
    ['🕒', 'Overall Start',    report.overallStartTime, ''],
    ['🏁', 'Overall End',      report.overallEndTime,   ''],
    ['⏱️', 'Duration',         report.overallDuration,  ''],
    ['📦', 'Total Raw Data',   report.totalRawData,     ''],
    ['❌', 'Total Error',      report.totalError,       'accent-error'],
    ['⚠️', 'Total Warning',    report.totalWarning,     'accent-warning'],
    ['✅', 'Total OK',         report.totalOk,          'accent-ok'],
    ['🚫', 'Total KO',         report.totalKo,          'accent-error'],
  ];
  return (
    <div className="summary-grid">
      {items.map(([icon, label, value, accent]) => (
        <div className={`summary-cell ${accent}`.trim()} key={label}>
          <span className="summary-icon" aria-hidden="true">{icon}</span>
          <span className="summary-label">{label}</span>
          <span className="summary-value">{value ?? 'N/A'}</span>
        </div>
      ))}
    </div>
  );
}

// ── Layer table ─────────────────────────────────────────────────────────────

function LayerTable({ title, icon, layers, showProgress }) {
  if (!layers || layers.length === 0) return null;
  const showExtras = hasExtraMetrics(layers);
  return (
    <div className="layer-section">
      <h3><span aria-hidden="true">{icon}</span> {title}</h3>
      <div className="table-wrapper">
        <table className="layer-table">
          <thead>
            <tr>
              <th>Layer Name</th>
              <th>Execution ID</th>
              <th>Start Time</th>
              <th>End Time</th>
              <th>Duration</th>
              <th>Raw Data</th>
              <th>Error</th>
              <th>Warning</th>
              <th>OK</th>
              <th>KO</th>
              <th>Status</th>
              {showExtras && <th>Reported</th>}
              {showProgress && <th>Last Processed</th>}
            </tr>
          </thead>
          <tbody>
            {layers.map((layer, idx) => (
              <tr key={`${layer.layerName}-${layer.executionId}-${idx}`}>
                <td className="layer-name-cell">{layer.layerName}</td>
                <td className="mono-cell">{layer.executionId ?? 'N/A'}</td>
                <td className="nowrap-cell">{layer.startTime ?? 'N/A'}</td>
                <td className="nowrap-cell">{layer.endTime ?? 'N/A'}</td>
                <td className="nowrap-cell">{layer.duration ?? 'N/A'}</td>
                <td className="num-cell">{layer.rawData ?? 'N/A'}</td>
                <td className="num-cell">{layer.errorCount ?? 'N/A'}</td>
                <td className="num-cell">{layer.warningCount ?? 'N/A'}</td>
                <td className="num-cell">{layer.okCount ?? 'N/A'}</td>
                <td className="num-cell">{layer.koCount ?? 'N/A'}</td>
                <td className="status-cell"><StatusBadge status={layer.status} /></td>
                {showExtras && <td>{formatExtraMetrics(layer) ?? 'N/A'}</td>}
                {showProgress && <td>{layer.progress ?? 'N/A'}</td>}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

// ── Current-progress banner ──────────────────────────────────────────────────

function CurrentProgressBanner({ report }) {
  if (report.overallStatus !== 'RUNNING') return null;
  const detail = report.currentProgressDetail;
  if (!detail && !report.currentProgress) return null;
  const hasCounts = detail && detail.processed != null && detail.total != null;
  return (
    <div className="current-progress-banner">
      <span aria-hidden="true">{ICON.running}</span>
      <div className="current-progress-body">
        <div className="current-progress-row">
          <span className="current-progress-label">Currently processing:</span>
          <span className="current-progress-value">{detail?.name || report.jobName || 'Unknown'}</span>
        </div>
        <div className="current-progress-meta">
          {detail?.startTime && <span>Started: {detail.startTime}</span>}
          {hasCounts && (
            <>
              <span>Processed: {detail.processed} / {detail.total} ({detail.percent}%)</span>
              <span>Remaining: {detail.remaining}</span>
            </>
          )}
          {!hasCounts && detail?.lastActivity && <span>Last activity: {detail.lastActivity}</span>}
        </div>
      </div>
    </div>
  );
}

// ── Copy button ──────────────────────────────────────────────────────────────

function CopyButton({ text }) {
  const [copied, setCopied] = useState(false);
  const resetTimerRef = useRef(null);
  useEffect(() => () => clearTimeout(resetTimerRef.current), []);
  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      clearTimeout(resetTimerRef.current);
      resetTimerRef.current = setTimeout(() => setCopied(false), COPIED_FEEDBACK_MS);
    } catch { /* unavailable */ }
  };
  return (
    <button type="button" className="copy-btn" onClick={handleCopy}>
      <span aria-hidden="true">{copied ? ICON.completed : ICON.clipboard}</span>{' '}
      {copied ? 'Copied' : 'Copy'}
    </button>
  );
}

// ── Source ref ───────────────────────────────────────────────────────────────

function SourceRef({ location }) {
  if (!location) return null;
  return (
    <span className="source-ref" title="File and line in the uploaded log">{location}</span>
  );
}

// ── Failure details ──────────────────────────────────────────────────────────

function FailureField({ label, value, location }) {
  if (!value) return null;
  return (
    <p>
      <span className="failure-label">{label}:</span> {value}{' '}
      <SourceRef location={location} />
    </p>
  );
}

function FailureCodeBlock({ label, text, location }) {
  if (!text) return null;
  return (
    <div className="stack-trace-block">
      <div className="stack-trace-header">
        <span className="failure-label">{label}: <SourceRef location={location} /></span>
        <CopyButton text={text} />
      </div>
      <pre className="stack-trace">{text}</pre>
    </div>
  );
}

function ErrorsDespiteSuccessBanner({ report }) {
  if (report.overallStatus === 'FAILED') return null;
  if (statusClass(report.overallStatus) === 'status-warning') return null;
  const errorIssues = (report.issues || []).filter((issue) => issue.severity === 'ERROR');
  const hasFailureDetail = report.mainError || report.stackTrace || report.failedComponent;
  if (errorIssues.length === 0 && !hasFailureDetail) return null;
  const occurrences = errorIssues.reduce((total, issue) => total + issue.occurrences, 0);
  const distinct = errorIssues.length;
  return (
    <div className="mismatch-banner" role="alert">
      <span aria-hidden="true">{ICON.warning}</span>
      <div>
        <strong>
          This run reported {report.overallStatus}, but {occurrences} error
          {occurrences === 1 ? ' was' : 's were'} logged
          {distinct > 1 ? ` across ${distinct} distinct causes` : ''}.
        </strong>{' '}
        Check the Errors &amp; Issues section below before treating this run as clean.
      </div>
    </div>
  );
}

function FailureDetails({ report }) {
  const where = report.sourceLocations || {};
  const fields = [
    ['DAG Name',         report.dagName,          null],
    ['Task Name',        report.taskName,          null],
    ['Job ID',           report.jobId,             null],
    ['ETL Layer',        report.failedLayerName,   null],
    ['Failed Component', report.failedComponent,   where.failedComponent],
    ['Main Error',       report.mainError,         where.mainError],
    ['Root Cause',       report.rootCause,         where.rootCause],
  ];
  const hasAnything = fields.some(([, value]) => value) || report.stackTrace || report.failedQuery;
  if (!hasAnything) return null;
  return (
    <div className="failure-details">
      <h3>
        <span aria-hidden="true">{ICON.warning}</span>{' '}
        {report.overallStatus === 'FAILED' ? 'Failure Details' : 'Errors Logged During the Run'}
      </h3>
      {fields.map(([label, value, location]) => (
        <FailureField key={label} label={label} value={value} location={location} />
      ))}
      <FailureCodeBlock label="Stack Trace" text={report.stackTrace} location={where.stackTrace} />
      <FailureCodeBlock label="Query"       text={report.failedQuery} location={where.failedQuery} />
    </div>
  );
}

// ── Talend components ────────────────────────────────────────────────────────

function TalendComponents({ components }) {
  if (!components || components.length === 0) return null;
  return (
    <div className="talend-components">
      <h3><span aria-hidden="true">{ICON.puzzle}</span> Talend Components</h3>
      <ul>{components.map((c) => <li key={c}>{c}</li>)}</ul>
    </div>
  );
}

// ── Format badge ─────────────────────────────────────────────────────────────

function FormatBadge({ report }) {
  if (!report.detectedFormat) return null;
  const percent = Math.round((report.formatConfidence ?? 0) * 100);
  const unmatched = report.detectedFormat === 'generic';
  return (
    <span
      className={`format-chip ${unmatched ? 'format-chip-generic' : ''}`.trim()}
      title={
        unmatched
          ? 'No known log format matched — figures come from generic analysis and may be incomplete'
          : `Parsed as ${report.detectedFormat} with ${percent}% confidence`
      }
    >
      {unmatched ? 'format: not recognised' : `format: ${report.detectedFormat} · ${percent}%`}
    </span>
  );
}

// ── Issues section (rich) ────────────────────────────────────────────────────

/**
 * Stable identity for an issue, independent of its position in the filtered list.
 *
 * Tracking expansion by list index meant that changing a filter left whichever issue now sat at
 * that index expanded instead of the one the user opened. The backend already folds repeats into
 * one issue per (source, first line, category), so that triple identifies a row.
 */
function issueKey(issue) {
  return `${issue.source}:${issue.firstLine}:${issue.category}`;
}

function issueMatchesSearch(issue, query) {
  const q = query.toLowerCase();
  return (
    issue.message.toLowerCase().includes(q) ||
    (issue.component || '').toLowerCase().includes(q) ||
    (issue.target     || '').toLowerCase().includes(q) ||
    issue.source.toLowerCase().includes(q) ||
    categoryLabel(issue.category).toLowerCase().includes(q)
  );
}

function IssueCard({ issue, isExpanded, onToggle }) {
  const isError = issue.severity === 'ERROR';
  return (
    <div
      className={`issue-card issue-card--${isError ? 'error' : 'warn'}${isExpanded ? ' issue-card--open' : ''}`}
      role="listitem"
    >
      {/* ─ collapsed row ─ */}
      <button
        className="issue-card-header"
        onClick={onToggle}
        aria-expanded={isExpanded}
      >
        <span className={`severity-tag severity-${issue.severity.toLowerCase()}`}>
          {isError ? ICON.failed : ICON.warning} {issue.severity}
        </span>
        <span className="ic-cat">{categoryLabel(issue.category)}</span>
        <span className="ic-occur">{issue.occurrences}×</span>
        <span className="source-ref">{issue.source}:{issue.firstLine}</span>
        <span className="ic-msg-preview">
          {issue.message.length > 130 ? issue.message.slice(0, 130) + '…' : issue.message}
        </span>
        <span className="ic-chevron" aria-hidden="true">{isExpanded ? '▲' : '▼'}</span>
      </button>

      {/* ─ expanded body ─ */}
      {isExpanded && (
        <div className="issue-card-body">

          {/* Full message */}
          <div className="ic-section">
            <div className="ic-label">Full message</div>
            <pre className="ic-code">{issue.message}</pre>
          </div>

          {/* Metadata grid */}
          <div className="ic-meta-grid">
            {issue.component && (
              <div className="ic-meta-row">
                <span className="ic-meta-key">Component</span>
                <code className="ic-meta-val">{issue.component}</code>
              </div>
            )}
            {issue.target && (
              <div className="ic-meta-row">
                <span className="ic-meta-key">Target</span>
                <code className="ic-meta-val">{issue.target}</code>
              </div>
            )}
            <div className="ic-meta-row">
              <span className="ic-meta-key">Category</span>
              <span className="ic-meta-val">{categoryLabel(issue.category)}</span>
            </div>
            <div className="ic-meta-row">
              <span className="ic-meta-key">Occurrences</span>
              <span className="ic-meta-val">
                {issue.occurrences}
                {issue.firstSeen && ` · first: ${issue.firstSeen}`}
                {issue.lastSeen && issue.lastSeen !== issue.firstSeen
                  ? ` → last: ${issue.lastSeen}` : ''}
              </span>
            </div>
            <div className="ic-meta-row">
              <span className="ic-meta-key">Location</span>
              <span className="ic-meta-val">
                <span className="source-ref">{issue.source}:{issue.firstLine}</span>
                {issue.lastLine > issue.firstLine && (
                  <span className="issue-span"> …{issue.lastLine}</span>
                )}
                {' '}<span className="ic-stream-label">{issue.source.toUpperCase()}</span>
              </span>
            </div>
          </div>

          {/* Context block */}
          {issue.context && issue.context.length > 0 && (
            <div className="ic-section">
              <div className="ic-label">
                {issue.source.toUpperCase()} context
                <span className="ic-label-sub"> · lines around first occurrence</span>
              </div>
              <div className="ic-context-block">
                {issue.context.map(({ lineNumber, text }) => {
                  const isErrorLine = lineNumber === issue.firstLine;
                  return (
                    <div
                      key={lineNumber}
                      className={`ctx-line${isErrorLine ? ' ctx-line--error' : ''}`}
                    >
                      <span className="ctx-num">{lineNumber}</span>
                      <span className="ctx-bar" aria-hidden="true">{isErrorLine ? '▶' : '│'}</span>
                      <span className="ctx-text">{text || ' '}</span>
                    </div>
                  );
                })}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function IssuesSection({ issues }) {
  const [search,         setSearch]         = useState('');
  const [severityFilter, setSeverityFilter] = useState('ALL');
  const [categoryFilter, setCategoryFilter] = useState('');
  const [expandedSet,    setExpandedSet]    = useState(() => new Set());

  const allIssues   = issues || [];
  const errorCount  = allIssues.filter((i) => i.severity === 'ERROR').length;
  const warnCount   = allIssues.filter((i) => i.severity === 'WARNING').length;
  const errorOccur  = allIssues.filter((i) => i.severity === 'ERROR')
                                .reduce((s, i) => s + i.occurrences, 0);

  // ── No issues → clean state ──────────────────────────────────────────────
  if (allIssues.length === 0) {
    return (
      <div className="issues-section">
        <h3 className="issues-section-title">
          <span aria-hidden="true">{ICON.warning}</span> Errors &amp; Issues
        </h3>
        <div className="issues-clean" role="status">
          <span className="issues-clean-check" aria-hidden="true">✓</span>
          <div>
            <strong>No issues detected</strong>
            <p>The log analysis found no errors or warnings that needed attention.</p>
          </div>
        </div>
      </div>
    );
  }

  const categories = [...new Set(allIssues.map((i) => i.category))].sort();

  const filtered = allIssues.filter((issue) => {
    if (severityFilter !== 'ALL' && issue.severity !== severityFilter) return false;
    if (categoryFilter && issue.category !== categoryFilter) return false;
    if (search && !issueMatchesSearch(issue, search)) return false;
    return true;
  });

  function toggleCard(key) {
    setExpandedSet((prev) => {
      const next = new Set(prev);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  }

  return (
    <div className="issues-section">

      {/* ── Header ── */}
      <div className="issues-section-header">
        <h3 className="issues-section-title">
          <span aria-hidden="true">{ICON.warning}</span> Errors &amp; Issues
        </h3>
        <div className="issues-summary-pills">
          {errorCount > 0 && (
            <span className="issues-pill issues-pill--error">
              ✕ {errorCount} error{errorCount !== 1 ? 's' : ''}
              {' '}({errorOccur} occurrence{errorOccur !== 1 ? 's' : ''})
            </span>
          )}
          {warnCount > 0 && (
            <span className="issues-pill issues-pill--warn">
              {ICON.warning} {warnCount} warning{warnCount !== 1 ? 's' : ''}
            </span>
          )}
          <span className="issues-pill issues-pill--total">
            {allIssues.length} distinct
          </span>
        </div>
      </div>

      {/* ── Toolbar ── */}
      <div className="issues-toolbar">
        <input
          className="search-input"
          placeholder="Search messages, components, targets…"
          value={search}
          onChange={(e) => { setSearch(e.target.value); }}
        />
        <div className="sev-pill-group" role="group" aria-label="Filter by severity">
          {[
            ['ALL',     'All'],
            ['ERROR',   `Errors (${errorCount})`],
            ['WARNING', `Warnings (${warnCount})`],
          ].map(([val, label]) => (
            <button
              key={val}
              className={`sev-pill${severityFilter === val ? ' sev-pill--active' : ''}${
                val !== 'ALL' ? ` sev-pill--${val.toLowerCase()}` : ''}`}
              onClick={() => setSeverityFilter(val)}
            >
              {label}
            </button>
          ))}
        </div>
        <select
          className="select-input"
          value={categoryFilter}
          onChange={(e) => setCategoryFilter(e.target.value)}
        >
          <option value="">All categories</option>
          {categories.map((c) => (
            <option key={c} value={c}>{categoryLabel(c)}</option>
          ))}
        </select>
        <span className="filter-count">{filtered.length} of {allIssues.length}</span>
        <div className="issues-expand-btns">
          <button
            className="issues-ctrl-btn"
            onClick={() => setExpandedSet(new Set(filtered.map(issueKey)))}
          >
            Expand all
          </button>
          <button
            className="issues-ctrl-btn"
            onClick={() => setExpandedSet(new Set())}
          >
            Collapse all
          </button>
        </div>
      </div>

      {/* ── Cards ── */}
      <div className="issues-list" role="list">
        {filtered.map((issue) => {
          const key = issueKey(issue);
          return (
            <IssueCard
              key={key}
              issue={issue}
              isExpanded={expandedSet.has(key)}
              onToggle={() => toggleCard(key)}
            />
          );
        })}
        {filtered.length === 0 && (
          <div className="issues-no-match">
            No issues match the current filters.
          </div>
        )}
      </div>
    </div>
  );
}

// ── Root report view ─────────────────────────────────────────────────────────

function ReportView({ report }) {
  if (!report) return null;

  return (
    <div className="report-view">
      {/* Header bar */}
      <div className="report-header">
        <h2 className="report-title">{report.jobName || 'Unknown Job'}</h2>
        <div className="report-header-row2">
          <div className="report-header-badges">
            <StatusBadge status={report.overallStatus} />
            <FormatBadge report={report} />
          </div>
          <button
            type="button"
            className="download-csv-btn"
            onClick={() => downloadReportCsv(report)}
          >
            <span aria-hidden="true">{ICON.download}</span> Download CSV
          </button>
        </div>
      </div>

      {/* Mismatch banner */}
      <ErrorsDespiteSuccessBanner report={report} />

      {/* Execution summary */}
      <SummaryGrid report={report} />
      <CurrentProgressBanner report={report} />

      {/* Layer tables */}
      <LayerTable
        title="Completed ETL Layers"
        icon={ICON.completed}
        layers={report.completedEtlLayers}
      />
      <LayerTable
        title="In-Progress ETL Layers"
        icon={ICON.running}
        layers={report.inProgressEtlLayers}
        showProgress
      />

      {/* Errors & Issues — always shown, clean state when none */}
      <IssuesSection issues={report.issues} />

      {/* Failure root-cause detail */}
      <FailureDetails report={report} />

      {/* Talend components */}
      <TalendComponents components={report.talendComponents} />
    </div>
  );
}

export default ReportView;
