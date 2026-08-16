package com.loganalyzer.model;

/**
 * One log dialect the analyzer can recognise, as reported to the frontend.
 *
 * <p>Backed by {@link com.loganalyzer.service.profile.ProfileRegistry#profiles()} rather than a
 * list maintained by hand, so the set of supported formats shown to the user can never drift from
 * what the backend actually detects.
 */
public record FormatInfoDto(String id, String displayName) {}
