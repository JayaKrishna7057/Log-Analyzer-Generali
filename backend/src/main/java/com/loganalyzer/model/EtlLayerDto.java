package com.loganalyzer.model;

import java.util.List;

/**
 * One unit of work as shown in the report - an ETL layer, an Airflow task, a Spark job.
 *
 * <p>The four count columns are the well-known ETL ones and stay for back-compatibility;
 * {@code metrics} carries whatever the producing format actually reported, and {@code targets}
 * records where the data went.
 */
public record EtlLayerDto(
        String layerName,
        String executionId,
        String startTime,
        String endTime,
        String duration,
        Integer rawData,
        Integer errorCount,
        Integer warningCount,
        Integer okCount,
        Integer koCount,
        String status,
        String progress,
        List<MetricDto> metrics,
        List<DataTargetDto> targets
) {
}
