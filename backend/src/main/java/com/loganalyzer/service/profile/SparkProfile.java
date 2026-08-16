package com.loganalyzer.service.profile;

import com.loganalyzer.model.MetricDto;
import com.loganalyzer.service.LogSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Apache Spark driver logs written with the stock log4j2 layout
 * {@code %d{yy/MM/dd HH:mm:ss} %p %c{1}: %m%n%ex}, giving lines such as
 * {@code 26/08/13 01:23:45 INFO DAGScheduler: Job 0 finished: save at Main.scala:42, took 1.2 s}.
 *
 * <p>Units are Spark jobs. Note that row counts are not part of this layout - they live in SQL
 * accumulators - so units report duration and outcome rather than volumes.
 */
public class SparkProfile implements LogProfile {

    private static final Pattern LINE_PATTERN = Pattern.compile(
            "^(?<ts>\\d{2}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}) (?<level>[A-Z]+) (?<logger>[^:]+): (?<msg>.*)$");

    private static final Pattern SPARK_LINE =
            Pattern.compile("^\\d{2}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2} (INFO|WARN|ERROR) ", Pattern.MULTILINE);
    private static final Pattern SPARK_LOGGERS =
            Pattern.compile("\\b(DAGScheduler|TaskSetManager|SparkContext|BlockManager|Executor)\\b");

    private static final Pattern JOB_START = Pattern.compile("Starting job: (.+?) at ");
    private static final Pattern JOB_FINISHED =
            Pattern.compile("Job (\\d+) finished: (.+?), took ([\\d.]+) s");
    private static final Pattern JOB_FAILED = Pattern.compile("Job (\\d+) failed");
    private static final Pattern STAGE_FAILURE =
            Pattern.compile("Job aborted due to stage failure: (.*)");
    private static final Pattern APP_NAME = Pattern.compile("Submitted application: (\\S+)");
    private static final Pattern LOST_EXECUTOR = Pattern.compile("Lost executor (\\S+)");

    @Override
    public String id() {
        return "spark-log4j2";
    }

    @Override
    public String displayName() {
        return "Spark driver log";
    }

    @Override
    public double detect(LogSource source) {

        if (!SPARK_LINE.matcher(source.allText).find()) {
            return 0;
        }

        return 0.5 + 0.5 * Parsing.signatureScore(source.allText, SPARK_LOGGERS, JOB_FINISHED);
    }

    @Override
    public List<ProcessingUnit> parse(LogSource source) {

        List<ProcessingUnit> units = new ArrayList<>();
        int lostExecutors = 0;
        LocalDateTime pendingStart = null;
        String pendingName = null;

        for (String line : source.allLines) {

            Matcher parsed = LINE_PATTERN.matcher(line.trim());
            if (!parsed.matches()) {
                continue;
            }

            String message = parsed.group("msg");
            LocalDateTime timestamp = Parsing.parseTimestamp(parsed.group("ts"));

            if (LOST_EXECUTOR.matcher(message).find()) {
                lostExecutors++;
            }

            Matcher started = JOB_START.matcher(message);
            if (started.find()) {
                pendingName = started.group(1);
                pendingStart = timestamp;
                continue;
            }

            Matcher finished = JOB_FINISHED.matcher(message);
            if (finished.find()) {
                units.add(job(finished.group(1), pendingName != null ? pendingName : finished.group(2),
                        pendingStart, timestamp, finished.group(3), null, lostExecutors));
                pendingName = null;
                pendingStart = null;
                continue;
            }

            Matcher failed = JOB_FAILED.matcher(message);
            if (failed.find()) {
                String cause = Parsing.findGroup(STAGE_FAILURE, message).orElse(null);
                units.add(job(failed.group(1), pendingName != null ? pendingName : "job " + failed.group(1),
                        pendingStart, timestamp, null, cause, lostExecutors));
                pendingName = null;
                pendingStart = null;
            }
        }

        // A job that started and never reported an outcome is still running.
        if (pendingName != null) {
            units.add(ProcessingUnit.named(pendingName).start(pendingStart).build());
        }

        return units;
    }

    private ProcessingUnit job(String jobId, String name, LocalDateTime start, LocalDateTime end,
                               String seconds, String failureCause, int lostExecutors) {

        ProcessingUnit.Builder unit = ProcessingUnit.named(name)
                .executionId(jobId)
                .start(start)
                .end(end)
                .status(seconds != null ? "SUCCESS" : "FAILED", seconds == null);

        if (seconds != null) {
            unit.metric("durationSeconds", "Duration (s)",
                    (int) Math.round(Double.parseDouble(seconds)), MetricDto.Kind.DURATION);
        }
        if (lostExecutors > 0) {
            unit.metric("lostExecutors", "Lost executors", lostExecutors, MetricDto.Kind.WARN);
        }
        if (failureCause != null) {
            unit.metric("failed", "Failed jobs", 1, MetricDto.Kind.ERROR);
        }

        return unit.build();
    }

    @Override
    public JobIdentity identify(LogSource source) {
        return JobIdentity.ofJob(Parsing.findGroup(APP_NAME, source.allText).orElse(null));
    }
}
