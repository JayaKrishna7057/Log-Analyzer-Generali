package com.loganalyzer.model;

/**
 * One counter reported by a unit of work, under the producer's own name.
 *
 * <p>A named list replaces the four fixed columns so that formats counting different things -
 * rows rejected, files loaded, bytes staged, models built - all have somewhere to land.
 * {@code kind} is what lets the UI total and colour them without knowing the format.
 */
public record MetricDto(
        String key,
        String label,
        long value,
        String kind
) {
    /**
     * Roles a metric can play, so unknown formats still roll up correctly.
     *
     * <p>{@code REJECTED} is kept apart from {@code ERROR}: a batch log reports KO as the count of
     * rows it refused, which is not the same figure as the number of errors raised, and adding
     * them together would overstate both.
     */
    public enum Kind {
        INPUT, OK, WARN, ERROR, REJECTED, SKIPPED, BYTES, DURATION, OTHER
    }

    public static MetricDto of(String key, String label, long value, Kind kind) {
        return new MetricDto(key, label, value, kind.name());
    }
}
