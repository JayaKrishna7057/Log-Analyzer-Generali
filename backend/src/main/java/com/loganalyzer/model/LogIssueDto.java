package com.loganalyzer.model;

import java.util.List;

/**
 * One distinct problem found in the log, with every repetition folded into {@code occurrences}.
 *
 * <p>A load that rejects thousands of rows for the same reason produces one issue with a large
 * count rather than thousands of lines, which is what makes a multi-MB log readable.
 *
 * @param source     which upload it came from, {@code stdout} or {@code stderr}
 * @param firstLine  1-based line number of the first occurrence within that file
 * @param lastLine   1-based line number of the last occurrence, equal to {@code firstLine}
 *                   when the problem happened only once
 * @param context    lines surrounding the first occurrence in its source file, for inline display
 */
public record LogIssueDto(
        String severity,
        String category,
        String message,
        String component,
        String target,
        int occurrences,
        String firstSeen,
        String lastSeen,
        String source,
        int firstLine,
        int lastLine,
        List<ContextLine> context
) {
    /** {@code stderr:412} - the reference to quote when showing someone the raw log. */
    public String location() {
        return source + ":" + firstLine;
    }

    /**
     * One line of context surrounding the first occurrence of this issue.
     *
     * @param lineNumber 1-based line number within the source file
     * @param text       the raw log line content
     */
    public record ContextLine(int lineNumber, String text) {}
}
