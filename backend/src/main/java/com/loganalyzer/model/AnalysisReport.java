package com.loganalyzer.model;

import java.util.List;

/**
 * The analysis of one upload.
 *
 * <p>{@code mode} is kept for back-compatibility with existing clients; {@code detectedFormat}
 * and {@code formatConfidence} are the precise answer to "how was this log read", and should be
 * shown so that a thin report is never mistaken for a healthy job.
 *
 * <p>The four {@code total*} fields are derived sums over the layers' metrics, so they keep
 * working for formats that count entirely different things.
 */
public record AnalysisReport(
        String mode,
        String detectedFormat,
        double formatConfidence,
        String jobName,
        String dagName,
        String taskName,
        String overallStatus,
        String overallStartTime,
        String overallEndTime,
        String overallDuration,
        int totalRawData,
        int totalError,
        int totalWarning,
        int totalOk,
        int totalKo,
        List<EtlLayerDto> completedEtlLayers,
        List<EtlLayerDto> inProgressEtlLayers,
        String currentProgress,
        CurrentProgressDto currentProgressDetail,
        String failedComponent,
        String failedLayerName,
        String jobId,
        String mainError,
        String rootCause,
        String stackTrace,
        String failedQuery,
        List<String> talendComponents,
        List<LogIssueDto> issues,
        SourceLocationsDto sourceLocations
) {
}
