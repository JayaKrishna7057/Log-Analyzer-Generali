package com.loganalyzer.model;

/**
 * Structured detail for whatever is currently running: identifies which layer/job it is,
 * when it started, and either a processed/total/remaining/percent breakdown (when the log
 * contains an explicit "Items processed: X/Y --> Z%" line) or a raw lastActivity line fallback.
 */
public record CurrentProgressDto(
        String name,
        String startTime,
        Integer processed,
        Integer total,
        Integer remaining,
        String percent,
        String lastActivity
) {
}
