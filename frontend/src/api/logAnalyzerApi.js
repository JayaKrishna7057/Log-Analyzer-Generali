// Point a build at another backend with VITE_API_BASE_URL (see .env.example).
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api';

const RETRY_DELAY_MS = 500;

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function readJsonResponse(response) {
  const text = await response.text();
  let data;
  try {
    data = text ? JSON.parse(text) : {};
  } catch {
    data = { error: text || 'Unexpected server response.' };
  }

  if (!response.ok) {
    throw new Error(data.error || `Request failed with status ${response.status}`);
  }

  return data;
}

async function postToBackend(url, formData) {
  const response = await fetch(url, { method: 'POST', body: formData });
  return readJsonResponse(response);
}

/**
 * Uploads log files and returns the combined analysis result.
 *
 * Returns a KoReportDto:
 *   { analysis: AnalysisReport, records: RecordStatusDto[], hasRecordLevelData: boolean }
 *
 * The `analysis` field contains the full log analysis (status, timing, issues, ETL layers).
 * The `records` field contains per-record outcomes when the log format supports them.
 *
 * @param {File|null} stdoutFile
 * @param {File|null} stderrFile
 * @returns {Promise<object>} KoReportDto
 */
export async function analyzeLogs(stdoutFile, stderrFile) {
  if (!stdoutFile && !stderrFile) {
    throw new Error('Select at least one log file to analyze.');
  }

  const formData = new FormData();
  if (stdoutFile) formData.append('stdout', stdoutFile);
  if (stderrFile) formData.append('stderr', stderrFile);

  try {
    return await postToBackend(`${API_BASE_URL}/analyze`, formData);
  } catch (err) {
    if (!(err instanceof TypeError)) {
      throw err;
    }
    await sleep(RETRY_DELAY_MS);
    try {
      return await postToBackend(`${API_BASE_URL}/analyze`, formData);
    } catch {
      throw new Error(
        'Could not reach the analysis server. Please check it is running and try again.'
      );
    }
  }
}

/**
 * Parses a single per-layer detail file attached from the ETL Layer Errors tab and returns a
 * LayerErrorDetailDto: { executionId, layerName, rawData, errorCount, warningCount, okCount,
 * status, records: [{ timestamp, recordKey, recordId, issues: [{ severity, code, message }] }] }.
 *
 * @param {File} file
 * @returns {Promise<object>} LayerErrorDetailDto
 */
export async function analyzeLayerDetail(file) {
  if (!file) {
    throw new Error('Select a file to analyze.');
  }

  const formData = new FormData();
  formData.append('file', file);

  try {
    return await postToBackend(`${API_BASE_URL}/layer-detail`, formData);
  } catch (err) {
    if (!(err instanceof TypeError)) {
      throw err;
    }
    await sleep(RETRY_DELAY_MS);
    try {
      return await postToBackend(`${API_BASE_URL}/layer-detail`, formData);
    } catch {
      throw new Error(
        'Could not reach the analysis server. Please check it is running and try again.'
      );
    }
  }
}

/**
 * Log dialects the backend can recognise: [{ id, displayName }, ...].
 *
 * Read from the backend rather than kept as a list in the frontend, so what the UI advertises as
 * supported can never drift from what the analyzer actually detects. Failures are swallowed to
 * an empty list — this only feeds an informational hint, never a required piece of the analyze flow.
 */
export async function getSupportedFormats() {
  try {
    const response = await fetch(`${API_BASE_URL}/formats`);
    if (!response.ok) return [];
    return await response.json();
  } catch {
    return [];
  }
}
