package com.loganalyzer.model;

import java.util.List;

/**
 * A KO Report derived entirely from uploaded log files.
 *
 * <p>{@code analysis} carries the full standard report (status, timing, issues, ETL layers).
 * {@code records} carries per-record status when the log format tracks individual items;
 * {@code hasRecordLevelData} tells the UI whether to show the records table or a "not available"
 * notice, so the frontend does not need to re-inspect the records list.
 */
public record KoReportDto(
        AnalysisReport analysis,
        List<RecordStatusDto> records,
        boolean hasRecordLevelData
) {}
