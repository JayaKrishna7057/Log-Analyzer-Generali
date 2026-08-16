package com.loganalyzer.service.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loganalyzer.model.MetricDto;
import com.loganalyzer.service.LogSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Structured logs in the Elastic Common Schema: one JSON object per line carrying
 * {@code @timestamp}, {@code log.level} and {@code message}, plus {@code error.type},
 * {@code error.message} and {@code error.stack_trace} when something failed.
 *
 * <p>This is the tier that removes guesswork entirely - fields are read, never scraped - so it is
 * scored above the text dialects and is worth enabling on any producer you control.
 */
public class EcsJsonProfile implements LogProfile {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Enough lines to tell a structured log from one that merely contains some JSON. */
    private static final int SAMPLE_LINES = 40;
    private static final double MIN_JSON_RATIO = 0.6;

    @Override
    public String id() {
        return "ecs-json";
    }

    @Override
    public String displayName() {
        return "Structured JSON (ECS)";
    }

    @Override
    public double detect(LogSource source) {

        int considered = 0;
        int matched = 0;

        for (String line : source.allLines) {
            if (line.isBlank()) {
                continue;
            }
            if (considered++ >= SAMPLE_LINES) {
                break;
            }
            JsonNode event = readEvent(line);
            if (event != null && field(event, "log.level") != null && field(event, "message") != null) {
                matched++;
            }
        }

        if (considered == 0) {
            return 0;
        }

        double ratio = (double) matched / considered;
        return ratio >= MIN_JSON_RATIO ? ratio : 0;
    }

    /** One unit per {@code service.name}, or a single unit when the log does not say. */
    @Override
    public List<ProcessingUnit> parse(LogSource source) {

        Map<String, Tally> byService = new LinkedHashMap<>();

        for (String line : source.allLines) {

            JsonNode event = readEvent(line);
            if (event == null) {
                continue;
            }

            String service = field(event, "service.name");
            Tally tally = byService.computeIfAbsent(
                    service != null ? service : "log stream", ignored -> new Tally());

            tally.record(field(event, "log.level"), Parsing.parseTimestamp(field(event, "@timestamp")));
        }

        List<ProcessingUnit> units = new ArrayList<>();
        byService.forEach((name, tally) -> units.add(tally.toUnit(name)));
        return units;
    }

    /**
     * The level, message and error fields of one event as a single line, so a reported issue
     * reads as the problem itself rather than as a JSON object.
     */
    @Override
    public String issueText(String line) {

        JsonNode event = readEvent(line);
        if (event == null) {
            return null;
        }

        StringBuilder text = new StringBuilder();
        appendIfPresent(text, field(event, "@timestamp"));
        appendIfPresent(text, field(event, "log.level"));
        appendIfPresent(text, field(event, "error.type"));
        appendIfPresent(text, field(event, "message"));
        appendIfPresent(text, field(event, "error.message"));

        return text.isEmpty() ? null : text.toString();
    }

    private static void appendIfPresent(StringBuilder text, String value) {
        if (value != null && !value.isBlank()) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(value);
        }
    }

    private JsonNode readEvent(String line) {

        String trimmed = line.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return null;
        }

        try {
            JsonNode node = MAPPER.readTree(trimmed);
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ECS permits a dotted key at the top level or the equivalent nested objects, so both are
     * tried before giving up.
     */
    private static String field(JsonNode event, String dottedName) {

        JsonNode direct = event.get(dottedName);
        if (direct != null && direct.isValueNode()) {
            return direct.asText();
        }

        JsonNode current = event;
        for (String part : dottedName.split("\\.")) {
            if (current == null) {
                return null;
            }
            current = current.get(part);
        }

        return current != null && current.isValueNode() ? current.asText() : null;
    }

    /** Running totals for one service in the stream. */
    private static final class Tally {

        private int errors;
        private int warnings;
        private int events;
        private LocalDateTime first;
        private LocalDateTime last;

        private void record(String level, LocalDateTime timestamp) {

            events++;

            if (level != null) {
                String upper = level.toUpperCase(Locale.ROOT);
                if (upper.startsWith("ERROR") || upper.startsWith("FATAL") || upper.startsWith("SEVERE")) {
                    errors++;
                } else if (upper.startsWith("WARN")) {
                    warnings++;
                }
            }

            if (timestamp != null) {
                if (first == null || timestamp.isBefore(first)) {
                    first = timestamp;
                }
                if (last == null || timestamp.isAfter(last)) {
                    last = timestamp;
                }
            }
        }

        private ProcessingUnit toUnit(String name) {
            return ProcessingUnit.named(name)
                    .start(first)
                    .end(last)
                    .status(errors > 0 ? "FAILED" : "SUCCESS", errors > 0)
                    .metric("events", "Events", events, MetricDto.Kind.INPUT)
                    .metric("errors", "Errors", errors, MetricDto.Kind.ERROR)
                    .metric("warnings", "Warnings", warnings, MetricDto.Kind.WARN)
                    .build();
        }
    }
}
