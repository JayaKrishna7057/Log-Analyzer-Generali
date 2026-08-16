package com.loganalyzer.service.profile;

import com.loganalyzer.model.MetricDto;
import com.loganalyzer.service.LogSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Apache Airflow task logs.
 *
 * <p>Lines look like
 * {@code [2024-07-15T14:18:46.143+0000] {taskinstance.py:1103} INFO - Dependencies all met...},
 * and the {@code {file.py:line}} marker is close to unique to Airflow, which makes detection cheap.
 * One log covers one task instance, so the whole file is a single unit of work.
 */
public class AirflowProfile implements LogProfile {

    private static final Pattern ORIGIN_MARKER = Pattern.compile("\\{[a-zA-Z_]+\\.py:\\d+\\}");
    private static final Pattern ISO_LINE_START = Pattern.compile("^\\[\\d{4}-\\d{2}-\\d{2}T", Pattern.MULTILINE);
    private static final Pattern AIRFLOW_WORDS =
            Pattern.compile("\\b(taskinstance|airflow|dag_id|task_id)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern LINE_PATTERN = Pattern.compile(
            "^\\[(?<ts>[^\\]]+)\\]\\s*\\{[^}]+\\}\\s*(?<level>[A-Z]+)\\s*-\\s*(?<msg>.*)$");

    private static final Pattern DAG_ID = Pattern.compile("dag_id=([^\\s,)\\]]+)");
    private static final Pattern TASK_ID = Pattern.compile("task_id=([^\\s,)\\]]+)");
    private static final Pattern RUN_ID = Pattern.compile("run_id=([^\\s,)\\]]+)");
    private static final Pattern TRY_NUMBER = Pattern.compile("try_number=(\\d+)");

    private static final Pattern MARK_SUCCESS = Pattern.compile("Marking task as SUCCESS");
    private static final Pattern MARK_FAILED = Pattern.compile("Marking task as FAILED");
    private static final Pattern EXIT_CODE = Pattern.compile("Task exited with return code (\\d+)");

    @Override
    public String id() {
        return "airflow-task";
    }

    @Override
    public String displayName() {
        return "Airflow task log";
    }

    @Override
    public double detect(LogSource source) {

        if (!ORIGIN_MARKER.matcher(source.allText).find()) {
            return 0;
        }

        return 0.5 + 0.5 * Parsing.signatureScore(source.allText, ISO_LINE_START, AIRFLOW_WORDS);
    }

    @Override
    public List<ProcessingUnit> parse(LogSource source) {

        String text = source.allText;

        String taskId = Parsing.findGroup(TASK_ID, text).orElse(null);
        String dagId = Parsing.findGroup(DAG_ID, text).orElse(null);
        String name = taskId != null ? taskId : (dagId != null ? dagId : "airflow task");

        ProcessingUnit.Builder unit = ProcessingUnit.named(name)
                .executionId(Parsing.findGroup(RUN_ID, text).orElse("N/A"))
                .start(firstTimestamp(source.allLines))
                .metric("attempt", "Attempt", Parsing.findInt(TRY_NUMBER, text), MetricDto.Kind.OTHER);

        LocalDateTime lastSeen = lastTimestamp(source.allLines);

        Integer exitCode = Parsing.findInt(EXIT_CODE, text);
        if (exitCode != null) {
            unit.metric("exitCode", "Exit code", exitCode, MetricDto.Kind.OTHER);
        }

        // Airflow states the outcome explicitly; fall back to the exit code when it does not.
        if (MARK_FAILED.matcher(text).find()) {
            unit.status("FAILED", true).end(lastSeen);
        } else if (MARK_SUCCESS.matcher(text).find()) {
            unit.status("SUCCESS", false).end(lastSeen);
        } else if (exitCode != null) {
            boolean failed = exitCode != 0;
            unit.status(failed ? "FAILED" : "SUCCESS", failed).end(lastSeen);
        }

        unit.metric("errorLines", "Error lines", countLevel(source.allLines, "ERROR"), MetricDto.Kind.ERROR);
        unit.metric("warnLines", "Warning lines", countLevel(source.allLines, "WARNING"), MetricDto.Kind.WARN);

        return List.of(unit.build());
    }

    @Override
    public JobIdentity identify(LogSource source) {
        return new JobIdentity(
                Parsing.findGroup(TASK_ID, source.allText).orElse(null),
                Parsing.findGroup(DAG_ID, source.allText).orElse(null),
                Parsing.findGroup(TASK_ID, source.allText).orElse(null));
    }

    /** Strips the {@code {file.py:line}} envelope so issues read cleanly. */
    @Override
    public String issueText(String line) {

        // The timestamp is kept so issues can report when they started; the classifier
        // strips it back off the text it displays.
        Matcher matcher = LINE_PATTERN.matcher(line.trim());
        return matcher.matches()
                ? matcher.group("ts") + " " + matcher.group("level") + " " + matcher.group("msg")
                : line;
    }

    private Integer countLevel(List<String> lines, String level) {

        int count = 0;
        for (String line : lines) {
            Matcher matcher = LINE_PATTERN.matcher(line.trim());
            if (matcher.matches() && matcher.group("level").equals(level)) {
                count++;
            }
        }

        return count == 0 ? null : count;
    }

    private LocalDateTime firstTimestamp(List<String> lines) {
        for (String line : lines) {
            LocalDateTime timestamp = timestampOf(line);
            if (timestamp != null) {
                return timestamp;
            }
        }
        return null;
    }

    private LocalDateTime lastTimestamp(List<String> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            LocalDateTime timestamp = timestampOf(lines.get(i));
            if (timestamp != null) {
                return timestamp;
            }
        }
        return null;
    }

    private LocalDateTime timestampOf(String line) {
        Matcher matcher = LINE_PATTERN.matcher(line.trim());
        return matcher.matches() ? Parsing.parseTimestamp(normalizeOffset(matcher.group("ts"))) : null;
    }

    /** Airflow writes {@code +0000}; ISO parsing wants {@code +00:00}. */
    private String normalizeOffset(String timestamp) {
        return timestamp.replaceAll("([+-]\\d{2})(\\d{2})$", "$1:$2");
    }
}
