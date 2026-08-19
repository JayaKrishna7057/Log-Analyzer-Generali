/**
 * Shared vocabulary for statuses and issue categories.
 *
 * Both report views classify the same backend statuses, so the rules live here rather than being
 * restated per component - two copies had already drifted, leaving a status such as
 * OK_WITH_WARNINGS rendered as a success in one view and as unknown in the other.
 */

/** Statuses the backend normalises to a plain success (see LogAnalyzerService#isPlainSuccessToken). */
const PLAIN_SUCCESS_TOKENS = new Set(['OK', 'SUCCESS', 'SUCCEEDED', 'COMPLETED', 'FINISHED_OK']);

/** Uppercase and drop punctuation so "STATUS : OK" and "ok" classify alike. */
function normalizeStatus(status) {
  return status.toUpperCase().replace(/[^A-Z0-9_]/g, '');
}

/**
 * True for the success family: the plain tokens plus the OK_ prefix convention used by
 * BatchLayerProfile (OK_WITH_WARNINGS) and the FINISHED_OK prefix used by ElabBatchProfile.
 */
function isSuccessFamily(token) {
  return PLAIN_SUCCESS_TOKENS.has(token)
      || token.startsWith('OK_')
      || token.startsWith('FINISHED_OK');
}

/**
 * CSS modifier for a status badge.
 *
 * A success that mentions warnings is reported as a warning rather than a clean success, so a run
 * that finished but logged problems does not read as spotless.
 */
export function statusClass(status) {
  if (!status) return 'status-unknown';

  const token = normalizeStatus(status);

  if (isSuccessFamily(token)) {
    return token.includes('WARNING') ? 'status-warning' : 'status-success';
  }
  if (token.includes('RUNNING') || token.includes('PROGRESS')) return 'status-running';
  if (token.includes('FAIL') || token.includes('KO') || token.includes('ERROR')) return 'status-failed';

  return 'status-unknown';
}

/** Human-readable names for the backend's IssueCategory enum. */
const CATEGORY_LABELS = {
  DATA_QUALITY: 'Data quality',
  CONNECTIVITY: 'Connectivity',
  RESOURCE:     'Resource',
  PERMISSION:   'Permission',
  SOURCE_DATA:  'Source data',
  CONFIG:       'Configuration',
  UNKNOWN:      'Unclassified',
};

/** Falls back to the raw value so a category added backend-side still renders. */
export function categoryLabel(category) {
  return CATEGORY_LABELS[category] || category;
}

/**
 * Splits a status like "FINISHED_OK_WARNINGS" into ["FINISHED", "OK", "WARNINGS"], so a badge that
 * has to wrap can only break between words - never mid-word (e.g. "FINISHED_O" / "K"), which is
 * what plain CSS word-breaking does to an underscore-joined string with no space to break at.
 */
export function statusWords(status) {
  return (status || 'N/A').split('_');
}

/** Rows rendered before the "show more" pager in the record and issue tables. */
export const PAGE_SIZE = 100;
