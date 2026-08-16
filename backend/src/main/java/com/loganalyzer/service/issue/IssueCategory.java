package com.loganalyzer.service.issue;

import java.util.List;
import java.util.regex.Pattern;

/**
 * What kind of problem a log line describes. The category is what makes a failure actionable -
 * who fixes a constraint violation is not who fixes a connection timeout.
 *
 * <p>Rules are evaluated in declaration order and the first match wins, so the specific
 * categories are declared before the broad ones.
 */
public enum IssueCategory {

    DATA_QUALITY(
            "violates not-null constraint",
            "null value in column",
            "violates foreign key constraint",
            "violates unique constraint",
            "violates check constraint",
            "duplicate key value",
            "value too long",
            "numeric field overflow",
            "invalid input syntax",
            "could not be parsed|unparseable date"),

    CONNECTIVITY(
            "connection refused",
            "connection reset",
            "connection timed out",
            "could not connect",
            "unknown host|unknownhostexception",
            "no route to host",
            "authentication failed|password authentication failed",
            "socket.{0,20}(closed|timeout)"),

    RESOURCE(
            "outofmemoryerror|out of memory",
            "java heap space",
            "gc overhead limit",
            "no space left on device|disk (is )?full",
            "quota exceeded",
            "too many connections",
            "connection pool.{0,30}exhausted"),

    PERMISSION(
            "permission denied",
            "access denied",
            "insufficient privilege",
            "not authorized|unauthorized",
            "must be owner of"),

    SOURCE_DATA(
            "no such file or directory",
            "filenotfoundexception",
            "file not found",
            "input file is empty|empty file",
            "malformed|could not parse row|parse error"),

    CONFIG(
            "missing (required )?(property|parameter|configuration)",
            "undefined context variable",
            "could not resolve placeholder",
            "invalid connection string",
            "no suitable driver"),

    /** Anything recognisably wrong that no rule above claimed. */
    UNKNOWN();

    private final List<Pattern> patterns;

    IssueCategory(String... regexes) {
        this.patterns = java.util.Arrays.stream(regexes)
                .map(regex -> Pattern.compile(regex, Pattern.CASE_INSENSITIVE))
                .toList();
    }

    boolean matches(String line) {
        return patterns.stream().anyMatch(pattern -> pattern.matcher(line).find());
    }

    /** The first category whose rules match, or {@link #UNKNOWN}. */
    public static IssueCategory classify(String line) {

        if (line == null || line.isBlank()) {
            return UNKNOWN;
        }

        for (IssueCategory category : values()) {
            if (category != UNKNOWN && category.matches(line)) {
                return category;
            }
        }

        return UNKNOWN;
    }
}
