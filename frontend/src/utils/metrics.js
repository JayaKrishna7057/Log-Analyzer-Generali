/**
 * Metrics a unit of work reported, beyond the five that have their own columns.
 *
 * Formats count things the fixed columns have no room for - records skipped rather than rejected,
 * a remainder the batch derived, files staged. Dropping them would hide figures needed to
 * reconcile a summary against its own declared total, so they are surfaced under their own labels.
 */

/** Metric keys that already have a dedicated column in the layer tables. */
const STANDARD_METRIC_KEYS = new Set(['raw', 'error', 'warning', 'ok', 'ko']);

export function extraMetrics(layer) {
  return (layer.metrics || []).filter((m) => !STANDARD_METRIC_KEYS.has(m.key));
}

/** True when any layer reported something beyond the standard columns. */
export function hasExtraMetrics(layers) {
  return (layers || []).some((layer) => extraMetrics(layer).length > 0);
}

/** "Unprocessed: -1, Partially processed: 0" — or null when the layer reported no extras. */
export function formatExtraMetrics(layer) {
  const extras = extraMetrics(layer);
  return extras.length === 0 ? null : extras.map((m) => `${m.label}: ${m.value}`).join(', ');
}
