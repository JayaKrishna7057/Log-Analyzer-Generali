package com.loganalyzer.service.profile;

import com.loganalyzer.model.RecordStatusDto;
import com.loganalyzer.service.LogSource;

import java.util.List;

/**
 * One log dialect the analyzer knows how to read.
 *
 * <p>Adding a format means adding an implementation and registering it - no change to the
 * analyzer itself. Implementations must be stateless: one instance serves every request.
 */
public interface LogProfile {

    /** Stable identifier reported to the user, e.g. {@code airflow-task}. */
    String id();

    /** Human-readable name for the UI. */
    String displayName();

    /**
     * How strongly this log looks like this dialect, from 0 (not at all) to 1 (certain).
     * Scores below {@link ProfileRegistry#MIN_CONFIDENCE} are ignored.
     */
    double detect(LogSource source);

    /**
     * Units of work found in the log, in reading order. An empty list is legitimate - it means
     * the dialect matched but the log carries no discrete units, and the analyzer will fall
     * back to whole-log analysis.
     */
    List<ProcessingUnit> parse(LogSource source);

    /** Job, DAG and task names as this dialect expresses them. */
    default JobIdentity identify(LogSource source) {
        return JobIdentity.EMPTY;
    }

    /**
     * Per-record processing outcomes found in the log, in reading order.
     *
     * <p>Only log formats that track individual record status (e.g. elaboration batch logs
     * with "IDDIALOGUE / STATUS: OK|KO" lines) implement this. The default returns an empty
     * list so the rest of the system can call it unconditionally without a type check.
     */
    default List<RecordStatusDto> parseRecords(LogSource source) {
        return List.of();
    }

    /**
     * The text of one log line as it should be classified into issues.
     *
     * <p>Dialects that wrap their message in envelope noise - a JSON object, or a timestamp and
     * source-file prefix - should override this to hand back just the message, so the reported
     * issue reads as the problem rather than as a line of the log file.
     *
     * <p>Return {@code null} to ignore the line entirely. This is deliberately a per-line
     * transform rather than a whole-file one: it keeps every result tied to its original line
     * number, which is what lets a report say exactly where to look.
     */
    default String issueText(String line) {
        return line;
    }
}
