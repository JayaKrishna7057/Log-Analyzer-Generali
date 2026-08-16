// Quote anything that would otherwise break the row: separators, quotes, or either
// line-break character (rows themselves are joined with CRLF).
function escapeCsv(value) {
  if (value === null || value === undefined) return '';
  const str = String(value);
  if (/["\r\n,]/.test(str)) {
    return `"${str.replace(/"/g, '""')}"`;
  }
  return str;
}

function row(...cols) {
  return cols.map(escapeCsv).join(',');
}

/**
 * Flattens an AnalysisReport into CSV text: summary, failure details, then ETL layer tables.
 * Starts with an Excel "sep=" directive so the file opens correctly regardless of the OS locale's
 * default list separator (many European locales default to ';', which otherwise dumps everything
 * into a single column).
 */
function reportToCsv(report) {
  const lines = ['sep=,'];

  lines.push(row('Section', 'Field', 'Value'));
  lines.push(row('Summary', 'Job Name', report.jobName));
  lines.push(row('Summary', 'Status', report.overallStatus));
  if (report.detectedFormat) {
    lines.push(row('Summary', 'Detected Format', report.detectedFormat));
    lines.push(
      row('Summary', 'Format Confidence', `${Math.round((report.formatConfidence ?? 0) * 100)}%`)
    );
  }
  lines.push(row('Summary', 'Start Time', report.overallStartTime));
  lines.push(row('Summary', 'End Time', report.overallEndTime));
  lines.push(row('Summary', 'Duration', report.overallDuration));
  lines.push(row('Summary', 'Total Raw Data', report.totalRawData));
  lines.push(row('Summary', 'Total Error', report.totalError));
  lines.push(row('Summary', 'Total Warning', report.totalWarning));
  lines.push(row('Summary', 'Total OK', report.totalOk));
  lines.push(row('Summary', 'Total KO', report.totalKo));
  if (report.currentProgress) {
    lines.push(row('Summary', 'Current Progress', report.currentProgress));
  }
  const progressDetail = report.currentProgressDetail;
  if (progressDetail) {
    if (progressDetail.startTime) {
      lines.push(row('Summary', 'Current Progress Started', progressDetail.startTime));
    }
    if (progressDetail.processed != null && progressDetail.total != null) {
      lines.push(row('Summary', 'Current Progress Processed', progressDetail.processed));
      lines.push(row('Summary', 'Current Progress Total', progressDetail.total));
      lines.push(row('Summary', 'Current Progress Remaining', progressDetail.remaining));
    }
  }

  const hasFailureInfo = [
    report.dagName,
    report.taskName,
    report.jobId,
    report.failedLayerName,
    report.failedComponent,
    report.mainError,
    report.rootCause,
    report.stackTrace,
    report.failedQuery,
  ].some(Boolean);

  if (hasFailureInfo) {
    const where = report.sourceLocations || {};
    lines.push('');
    // The extra column is where the value was read from, e.g. "stderr:412".
    lines.push(row('Section', 'Field', 'Value', 'Found At'));
    lines.push(row('Failure', 'DAG Name', report.dagName, ''));
    lines.push(row('Failure', 'Task Name', report.taskName, ''));
    lines.push(row('Failure', 'Job ID', report.jobId, ''));
    lines.push(row('Failure', 'ETL Layer', report.failedLayerName, ''));
    lines.push(row('Failure', 'Failed Component', report.failedComponent, where.failedComponent));
    lines.push(row('Failure', 'Main Error', report.mainError, where.mainError));
    lines.push(row('Failure', 'Root Cause', report.rootCause, where.rootCause));
    if (report.stackTrace) {
      lines.push(row('Failure', 'Stack Trace', report.stackTrace, where.stackTrace));
    }
    if (report.failedQuery) {
      lines.push(row('Failure', 'Failed Query', report.failedQuery, where.failedQuery));
    }
  }

  if (report.issues && report.issues.length > 0) {
    lines.push('');
    lines.push(
      row(
        'Severity',
        'Category',
        'Occurrences',
        'Source File',
        'First Line',
        'Last Line',
        'Component',
        'Target',
        'Message',
        'First Seen',
        'Last Seen'
      )
    );
    report.issues.forEach((issue) => {
      lines.push(
        row(
          issue.severity,
          issue.category,
          issue.occurrences,
          issue.source,
          issue.firstLine,
          issue.lastLine,
          issue.component,
          issue.target,
          issue.message,
          issue.firstSeen,
          issue.lastSeen
        )
      );
    });
  }

  const layerRows = [
    ...(report.completedEtlLayers || []).map((l) => ['Completed', l]),
    ...(report.inProgressEtlLayers || []).map((l) => ['In Progress', l]),
  ];

  if (layerRows.length > 0) {
    lines.push('');
    lines.push(
      row(
        'Layer Type',
        'Layer Name',
        'Execution ID',
        'Start Time',
        'End Time',
        'Duration',
        'Raw Data',
        'Error',
        'Warning',
        'OK',
        'KO',
        'Status',
        'Progress'
      )
    );
    layerRows.forEach(([type, l]) => {
      lines.push(
        row(
          type,
          l.layerName,
          l.executionId,
          l.startTime,
          l.endTime,
          l.duration,
          l.rawData,
          l.errorCount,
          l.warningCount,
          l.okCount,
          l.koCount,
          l.status,
          l.progress
        )
      );
    });
  }

  return lines.join('\r\n');
}

/** Triggers a browser download of `csv` text under `filename`, UTF-8 BOM prefixed for Excel. */
function downloadCsvText(csv, filename) {
  const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');

  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  // Revoking synchronously can cancel the download in some browsers, so let the
  // click be processed first.
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

function safeFileName(name, fallback) {
  return (name || fallback).replace(/[^A-Za-z0-9_-]+/g, '_');
}

export function downloadReportCsv(report) {
  const csv = reportToCsv(report);
  downloadCsvText(csv, `${safeFileName(report.jobName, 'log_analysis_report')}.csv`);
}

/**
 * Flattens a KoReportDto into CSV: the same analysis summary as {@link downloadReportCsv}, plus
 * every per-record outcome when the format tracks them. Kept as one file rather than two so a KO
 * report shared with a teammate carries both the run's headline figures and the record detail
 * that explains them.
 */
function koReportToCsv(koReport) {
  const lines = [reportToCsv(koReport.analysis)];

  if (koReport.hasRecordLevelData && koReport.records?.length > 0) {
    lines.push('');
    lines.push(row('Dialogue ID', 'Internal Key', 'Unique Code', 'Status', 'KO Reason'));
    koReport.records.forEach((r) => {
      lines.push(row(r.dialogueId, r.internalKey, r.uniqueCode, r.status, r.koReason));
    });
  }

  return lines.join('\r\n');
}

export function downloadKoReportCsv(koReport) {
  const csv = koReportToCsv(koReport);
  downloadCsvText(csv, `${safeFileName(koReport.analysis?.jobName, 'ko_report')}.csv`);
}
