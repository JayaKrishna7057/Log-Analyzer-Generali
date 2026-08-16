package com.loganalyzer.service.profile;

import com.loganalyzer.model.MetricDto;
import com.loganalyzer.service.LogSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Batch ETL logs built from "START &lt;LAYER&gt; ... STATUS : OK" blocks, each carrying an
 * IDEXECUTION id and Raw data / Error / Warning / OK counters.
 */
public class BatchLayerProfile implements LogProfile {

    /** The trailing lookahead skips the "START : &lt;timestamp&gt;" header line. */
    private static final Pattern START_MARKER =
            Pattern.compile("\\bSTART\\s+([A-Za-z0-9_]+)\\b(?!\\s*:)");
    private static final Pattern IDEXECUTION_PATTERN =
            Pattern.compile("IDEXECUTION\\s*:\\s*(\\d+)");
    private static final Pattern BATCH_PATTERN =
            Pattern.compile("\\bBATCH\\s*:");
    private static final Pattern HEADER_START_PATTERN =
            Pattern.compile("\\bSTART\\s*:\\s*([\\d/]{8,10}\\s+[\\d:]{5,8})");
    private static final Pattern PROC_START_PATTERN =
            Pattern.compile("Starting time\\s*:\\s*([\\d/]{8,10}\\s+[\\d:]{5,8})");
    private static final Pattern PROC_END_PATTERN =
            Pattern.compile("End time\\s*:\\s*([\\d/]{8,10}\\s+[\\d:]{5,8})");
    private static final Pattern RAW_PATTERN = Pattern.compile("Raw data\\s*:\\s*(\\d+)");
    private static final Pattern ERROR_PATTERN = Pattern.compile("-\\s*Error\\s*:\\s*(\\d+)");
    private static final Pattern WARNING_PATTERN = Pattern.compile("-\\s*Warning\\s*:\\s*(\\d+)");
    private static final Pattern OK_PATTERN = Pattern.compile("-\\s*OK\\s*:\\s*(\\d+)");

    /**
     * Rows the layer refused. The dash is optional so both "- KO : 12" and "KO : 12" are read,
     * and requiring digits keeps this off the "STATUS : KO" line, where KO is an outcome rather
     * than a count.
     */
    private static final Pattern KO_PATTERN = Pattern.compile("\\bKO\\s*:\\s*(\\d+)");
    private static final Pattern STATUS_PATTERN = Pattern.compile("\\bSTATUS\\s*:\\s*(\\S+)");

    /**
     * A STATUS counts as success only when it reads as one. Everything else is a failure, which
     * is what keeps KO, NOT_OK, FAILED, ERROR and ABORTED on the failing side - matching on a
     * bare "OK" substring would quietly pass NOT_OK.
     */
    private static final Set<String> SUCCESS_STATUSES =
            Set.of("OK", "SUCCESS", "SUCCEEDED", "COMPLETED");

    @Override
    public String id() {
        return "batch-layer";
    }

    @Override
    public String displayName() {
        return "Batch ETL layer log";
    }

    @Override
    public double detect(LogSource source) {

        // A START block is what makes this log parsable at all; the rest only sharpen confidence.
        if (!START_MARKER.matcher(source.allText).find()) {
            return 0;
        }

        return 0.6 + 0.4 * Parsing.signatureScore(source.allText,
                IDEXECUTION_PATTERN, STATUS_PATTERN, BATCH_PATTERN);
    }

    /**
     * Splits the log at every "START &lt;LAYER&gt;" marker; a block runs to the next marker or to
     * the end of the log.
     */
    @Override
    public List<ProcessingUnit> parse(LogSource source) {

        String text = source.allText;

        List<Integer> blockStarts = new ArrayList<>();
        List<String> names = new ArrayList<>();

        Matcher startMatcher = START_MARKER.matcher(text);
        while (startMatcher.find()) {
            blockStarts.add(startMatcher.start());
            names.add(startMatcher.group(1));
        }

        List<ProcessingUnit> units = new ArrayList<>(blockStarts.size());

        for (int i = 0; i < blockStarts.size(); i++) {
            int end = (i + 1 < blockStarts.size()) ? blockStarts.get(i + 1) : text.length();
            units.add(parseBlock(names.get(i), text.substring(blockStarts.get(i), end)));
        }

        return units;
    }

    private ProcessingUnit parseBlock(String name, String block) {

        ProcessingUnit.Builder unit = ProcessingUnit.named(name);

        unit.executionId(Parsing.findGroup(IDEXECUTION_PATTERN, block).orElse("N/A"));

        LocalDateTime headerStart = Parsing.findTimestamp(HEADER_START_PATTERN, block);
        unit.start(headerStart != null ? headerStart : Parsing.findTimestamp(PROC_START_PATTERN, block));
        unit.end(Parsing.findTimestamp(PROC_END_PATTERN, block));

        unit.metric("raw", "Raw data", Parsing.findInt(RAW_PATTERN, block), MetricDto.Kind.INPUT);
        unit.metric("error", "Error", Parsing.findInt(ERROR_PATTERN, block), MetricDto.Kind.ERROR);
        unit.metric("warning", "Warning", Parsing.findInt(WARNING_PATTERN, block), MetricDto.Kind.WARN);
        unit.metric("ok", "OK", Parsing.findInt(OK_PATTERN, block), MetricDto.Kind.OK);
        unit.metric("ko", "KO", Parsing.findInt(KO_PATTERN, block), MetricDto.Kind.REJECTED);

        Parsing.findGroup(STATUS_PATTERN, block)
                .ifPresent(status -> unit.status(status, !isSuccess(status)));

        Parsing.Progress progress = Parsing.lastProgress(block);
        if (progress != null) {
            unit.progress(progress.processed(), progress.total(), progress.percent(), progress.text());
        }

        return unit.build();
    }

    private boolean isSuccess(String status) {
        String normalized = status.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "");
        return SUCCESS_STATUSES.contains(normalized) || normalized.startsWith("OK_");
    }
}
