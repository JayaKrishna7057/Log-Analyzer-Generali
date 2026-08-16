package com.loganalyzer.model;

/**
 * The outcome of processing one record as found in a log that tracks per-item status.
 *
 * <p>Fields that the log does not provide are null rather than fabricated.
 *
 * @param dialogueId  the record's unique dialogue / message identifier
 * @param internalKey the internal application key for this record, or "-" when absent
 * @param uniqueCode  the business-facing unique code for this record
 * @param status      the processing outcome: "OK" or "KO"
 * @param koReason    the rejection reason extracted from the log; null when status is OK
 */
public record RecordStatusDto(
        String dialogueId,
        String internalKey,
        String uniqueCode,
        String status,
        String koReason
) {}
