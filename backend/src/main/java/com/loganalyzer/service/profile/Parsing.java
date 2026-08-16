package com.loganalyzer.service.profile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parsing helpers shared by the profiles and the analyzer. */
public final class Parsing {

    private static final DateTimeFormatter[] TIMESTAMP_FORMATS = {
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME
    };

    private Parsing() {
    }

    /** The first capture group of {@code pattern} in {@code text}, if it matches at all. */
    public static Optional<String> findGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    public static LocalDateTime findTimestamp(Pattern pattern, String text) {
        return findGroup(pattern, text).map(Parsing::parseTimestamp).orElse(null);
    }

    public static Integer findInt(Pattern pattern, String text) {
        return findGroup(pattern, text).map(Parsing::parseIntOrNull).orElse(null);
    }

    /**
     * Sum of the first capture group over every match, or {@code null} when nothing matched.
     *
     * <p>For summary blocks that split one figure across several lines - a base count plus
     * qualified variants of it. Matching the family rather than each known line means a log that
     * reports work under a qualifier this analyzer has never seen is still counted.
     */
    public static Integer sumInts(Pattern pattern, String text) {

        Matcher matcher = pattern.matcher(text);
        long total = 0;
        boolean matched = false;

        while (matcher.find()) {
            Integer value = parseIntOrNull(matcher.group(1));
            if (value != null) {
                total += value;
                matched = true;
            }
        }

        return matched ? (int) Math.max(Integer.MIN_VALUE, Math.min(total, Integer.MAX_VALUE)) : null;
    }

    /** Digit runs in a log can exceed {@code int}; an unparsable count is absent, not an error. */
    public static Integer parseIntOrNull(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses any layout this analyzer understands, including the offset-bearing ISO stamps that
     * orchestrators emit; the offset is dropped so every timestamp in a report is comparable.
     */
    public static LocalDateTime parseTimestamp(String raw) {

        if (raw == null) {
            return null;
        }

        String trimmed = raw.trim();

        for (DateTimeFormatter formatter : TIMESTAMP_FORMATS) {
            try {
                return LocalDateTime.parse(trimmed, formatter);
            } catch (DateTimeParseException ignored) {
                // try the next supported layout
            }
        }

        return null;
    }

    /** Case-insensitive {@code contains} that allocates nothing. */
    public static boolean containsIgnoreCase(String text, String needle) {

        int lastStart = text.length() - needle.length();

        for (int i = 0; i <= lastStart; i++) {
            if (text.regionMatches(true, i, needle, 0, needle.length())) {
                return true;
            }
        }

        return false;
    }

    private static final Pattern PROGRESS_PATTERN =
            Pattern.compile("Items processed:\\s*(\\d+)\\s*/\\s*(\\d+)\\s*-->\\s*([\\d.]+)%");

    /** A processed/total/percent reading taken from an "Items processed" line. */
    public record Progress(Integer processed, Integer total, String percent) {
        public String text() {
            return processed + "/" + total + " (" + percent + "%)";
        }
    }

    /** The last "Items processed" line in {@code text} - the freshest figure - or {@code null}. */
    public static Progress lastProgress(String text) {

        Matcher matcher = PROGRESS_PATTERN.matcher(text);
        Progress last = null;

        while (matcher.find()) {
            Integer processed = parseIntOrNull(matcher.group(1));
            Integer total = parseIntOrNull(matcher.group(2));
            if (processed != null && total != null) {
                last = new Progress(processed, total, matcher.group(3));
            }
        }

        return last;
    }

    /** Fraction of {@code patterns} that appear in {@code text} - the basis of profile scoring. */
    public static double signatureScore(String text, Pattern... patterns) {

        if (patterns.length == 0) {
            return 0;
        }

        int hits = 0;
        for (Pattern pattern : patterns) {
            if (pattern.matcher(text).find()) {
                hits++;
            }
        }

        return (double) hits / patterns.length;
    }
}
