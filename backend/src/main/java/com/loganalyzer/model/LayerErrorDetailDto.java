package com.loganalyzer.model;

import java.util.List;

/**
 * The parsed contents of one per-layer detail file, attached from the ETL Layer Errors tab.
 *
 * <p>{@code executionId} is what ties this back to the {@link EtlLayerDto} row it was attached
 * to - both come from the same {@code IDEXECUTION} the batch stamps into every log it writes for
 * that run.
 */
public record LayerErrorDetailDto(
        String executionId,
        String layerName,
        Integer rawData,
        Integer errorCount,
        Integer warningCount,
        Integer okCount,
        String status,
        List<LayerErrorRecordDto> records
) {}
