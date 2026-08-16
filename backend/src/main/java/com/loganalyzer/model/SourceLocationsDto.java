package com.loganalyzer.model;

/**
 * Where each failure detail was taken from, as {@code stdout:12} or {@code stderr:412}.
 *
 * <p>The analyzer searches stderr before stdout, so two runs of the same job can legitimately
 * quote different files. Stating the origin makes every extracted value checkable against the
 * raw log rather than something the reader has to take on trust.
 *
 * <p>Each field is {@code null} when the matching detail was not found.
 */
public record SourceLocationsDto(
        String failedComponent,
        String mainError,
        String rootCause,
        String stackTrace,
        String failedQuery
) {
    public static final SourceLocationsDto NONE = new SourceLocationsDto(null, null, null, null, null);
}
