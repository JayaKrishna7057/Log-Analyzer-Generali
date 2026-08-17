package com.loganalyzer.model;

/**
 * One severity-tagged line attached to a record in a per-layer detail file, e.g.
 * {@code E - ErroreSoggettoRefereziatoNonTrovato --> Soggetto referenziato 495696518 non trovato}.
 *
 * @param severity "ERROR" or "WARNING"
 * @param code     the batch's own error code, e.g. {@code ErroreSoggettoRefereziatoNonTrovato}
 * @param message  the human-readable explanation
 */
public record LayerErrorIssueDto(String severity, String code, String message) {}
