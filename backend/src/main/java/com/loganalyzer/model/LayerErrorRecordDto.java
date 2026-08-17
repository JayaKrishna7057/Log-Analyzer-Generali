package com.loganalyzer.model;

import java.util.List;

/**
 * One record's failure detail from a per-layer detail file, keyed by whatever field that layer
 * identifies records with - {@code IDMOV}, {@code IDPARTYLOCK}, {@code TABLE}, and so on. The
 * combined multi-layer stdout only ever prints the aggregate count for these; this is the
 * per-record breakdown behind that count.
 *
 * @param recordKey the identifying field's name, e.g. {@code IDMOV}
 * @param recordId  the identifying field's value, e.g. {@code 720479980}
 */
public record LayerErrorRecordDto(
        String timestamp,
        String recordKey,
        String recordId,
        List<LayerErrorIssueDto> issues
) {}
