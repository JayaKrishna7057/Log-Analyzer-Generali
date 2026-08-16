package com.loganalyzer.model;

/**
 * Something the job wrote to or read from - a table, a file, a topic.
 *
 * <p>This is what lets a report answer "which table lost rows, and why" instead of only
 * "the job failed". Counts are nullable because most logs mention a target without stating
 * every figure for it.
 */
public record DataTargetDto(
        String name,
        String kind,
        Long read,
        Long written,
        Long rejected
) {
    public enum Kind {
        TABLE, FILE, TOPIC, UNKNOWN
    }

    public static DataTargetDto of(String name, Kind kind, Long read, Long written, Long rejected) {
        return new DataTargetDto(name, kind.name(), read, written, rejected);
    }
}
