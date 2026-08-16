package com.loganalyzer.service;

import com.loganalyzer.model.AnalysisReport;
import com.loganalyzer.model.CurrentProgressDto;
import com.loganalyzer.model.EtlLayerDto;
import com.loganalyzer.model.LogIssueDto;
import com.loganalyzer.model.MetricDto;
import com.loganalyzer.model.SourceLocationsDto;
import com.loganalyzer.service.issue.IssueClassifier;
import com.loganalyzer.service.profile.JobIdentity;
import com.loganalyzer.service.profile.Parsing;
import com.loganalyzer.service.profile.ProcessingUnit;
import com.loganalyzer.service.profile.ProfileMatch;
import com.loganalyzer.service.profile.ProfileRegistry;
import com.loganalyzer.service.profile.ProfileSelection;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a pair of uploaded batch logs into an {@link AnalysisReport}.
 *
 * <p>The dialect is chosen by {@link ProfileRegistry} rather than hard-coded here, so supporting a
 * new log format means adding a profile. Whatever the profile produces is normalised through one
 * path: units of work roll up into status, timings and totals, while whole-log analysis supplies
 * the failure details and the classified issue list.
 *
 * <p>When no profile matches confidently the generic analysis still runs, and the report says
 * which format was used and how sure it was - a thin report must never read as a healthy job.
 *
 * <p>The service is stateless; all per-request state lives in the {@link LogSource}.
 */
@Service
public class LogAnalyzerService {

    // ---- Identification ------------------------------------------------------------------

    /**
     * Job name candidates, in precedence order. Each regex is paired with a literal it cannot
     * match without, because running all of them unguarded over a multi-MB log dominated the
     * cost of an analysis - the last one alone restarts a greedy run at every character.
     */
    private static final GuardedPattern[] JOB_NAME_PATTERNS = {
            GuardedPattern.of("BATCH", "\\*\\*\\*\\*\\s*BATCH\\s*:\\s*(\\S+?)\\s*\\*\\*\\*\\*"),
            GuardedPattern.of("BATCH", "\\bBATCH\\s*:\\s*(\\S+)"),
            GuardedPattern.of("job", "(?:Starting|Launching|Executing|Running)\\s+job\\s+([A-Za-z0-9_]+)"),
            GuardedPattern.of("job", "Job(?:\\s+name)?\\s*[:=]\\s*([A-Za-z0-9_]+)"),
            GuardedPattern.of("jobname", "JobName\\s*[:=]\\s*([A-Za-z0-9_]+)"),
            GuardedPattern.of("talendjobname", "TalendJobName\\s*[:=]\\s*([A-Za-z0-9_]+)"),
            GuardedPattern.of("exception in component", "Exception in component .*?\\((.*?)\\)"),
            GuardedPattern.of("_Postgres_", "([A-Za-z0-9_]+_Postgres_\\d+)"),
            GuardedPattern.of("AZ_BATCH_JOB_ID", "AZ_BATCH_JOB_ID\\s*[:=]\\s*(\\S+)")
    };

    /**
     * A regex plus a literal that must appear in the text for the regex to have any chance of
     * matching. Skipping ruled-out patterns cannot change which pattern wins, since the guard is
     * a necessary condition for that same pattern.
     */
    private record GuardedPattern(String requiredText, Pattern pattern) {

        static GuardedPattern of(String requiredText, String regex) {
            return new GuardedPattern(requiredText, Pattern.compile(regex));
        }

        Optional<String> firstGroup(String text) {

            if (!Parsing.containsIgnoreCase(text, requiredText)) {
                return Optional.empty();
            }

            Matcher matcher = pattern.matcher(text);
            return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
        }
    }

    private static final String UNKNOWN_JOB_NAME = "Unknown";

    private static final Pattern EXCEPTION_COMPONENT_PATTERN =
            Pattern.compile("Exception in component\\s+(\\S+)");
    private static final Pattern DAG_NAME_PATTERN =
            Pattern.compile("AZ_BATCH_JOB_ID\\s*[:=]\\s*(\\S+)");
    private static final Pattern TASK_NAME_PATTERN =
            Pattern.compile("Execution\\s+\"([^\"]+)\"");
    private static final Pattern TALEND_COMPONENT_PATTERN =
            Pattern.compile("(t[A-Za-z0-9]+_\\d+)");
    private static final Pattern JOB_ID_PREFIX_PATTERN =
            Pattern.compile("^(?:stdout|stderr)[_-](.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern JOB_ID_SUFFIX_PATTERN =
            Pattern.compile("^(.+)[_-](?:stdout|stderr)$", Pattern.CASE_INSENSITIVE);

    // ---- Generic fallback ----------------------------------------------------------------

    private static final String TIMESTAMP_REGEX =
            "(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}|\\d{2}/\\d{2}/\\d{4}\\s+\\d{2}:\\d{2}:\\d{2})";

    private static final Pattern SQL_MUTATION_PATTERN =
            Pattern.compile("\\b(insert into|update|delete from|values\\s*\\()", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUERY_EXEC_PATTERN =
            Pattern.compile("Query\\s+exec", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEGACY_TS_LEADING = Pattern.compile("^\\s*" + TIMESTAMP_REGEX);
    private static final Pattern LEGACY_TS_BROAD = Pattern.compile(TIMESTAMP_REGEX);

    /** Markers that put a generic log in FAILED / SUCCESS; "exception" also covers SQLException. */
    private static final String[] LEGACY_FAILURE_MARKERS = {"[fatal]", "exception"};
    private static final String[] LEGACY_SUCCESS_MARKERS = {"job completed", "job success"};

    private static final String EXIT_CODE_MARKER = "exit code";
    private static final Pattern EXIT_CODE_FAILURE =
            Pattern.compile("EXIT CODE.*1", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXIT_CODE_SUCCESS =
            Pattern.compile("EXIT CODE.*0", Pattern.CASE_INSENSITIVE);

    // ---- Report vocabulary ---------------------------------------------------------------

    private static final String NOT_AVAILABLE = "N/A";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FINISHED_OK_WARNINGS = "FINISHED_OK_WARNINGS";
    private static final String STATUS_IN_PROGRESS = "IN PROGRESS";

    private static final int MAX_STACK_TRACE_LINES = 80;
    private static final int MAX_QUERY_LINES = 40;
    private static final int MAX_LAST_ACTIVITY_LENGTH = 200;

    private final ProfileRegistry profileRegistry;
    private final IssueClassifier issueClassifier = new IssueClassifier();

    public LogAnalyzerService(ProfileRegistry profileRegistry) {
        this.profileRegistry = profileRegistry;
    }

    /**
     * One analysis together with the inputs it was derived from.
     *
     * <p>Callers that need more than the report - the KO report needs per-record data from the
     * same profile - take the {@link LogSource} and {@link ProfileSelection} from here rather than
     * re-reading the upload and re-running detection, which would risk resolving a different
     * profile than the one the report names.
     */
    public record Analysis(AnalysisReport report, LogSource source, ProfileSelection selection) {}

    /**
     * Reads the uploads once and analyses them, returning the report along with the log source and
     * the profile that produced it.
     */
    public Analysis analyzeUploads(MultipartFile stdoutFile, MultipartFile stderrFile) throws IOException {
        LogSource source = LogSource.read(stdoutFile, stderrFile);
        return analyze(source, extractJobId(stdoutFile, stderrFile));
    }

    /** Analyses an already-read log source. {@code filenameJobId} may be null. */
    public Analysis analyze(LogSource source, String filenameJobId) {

        ProfileSelection selection = profileRegistry.select(source);
        ProfileMatch match = selection.match();
        List<ProcessingUnit> units = selection.units();
        JobIdentity identity = match.matched() ? match.profile().identify(source) : JobIdentity.EMPTY;

        // The profile decides what an "event" looks like, so issues read as problems, not log lines.
        List<LogIssueDto> issues =
                issueClassifier.classify(source, match.matched() ? match.profile() : null);

        AnalysisReport report = units.isEmpty()
                ? analyzeWholeLog(source, filenameJobId, match, issues)
                : analyzeUnits(units, source, filenameJobId, match, identity, issues);

        return new Analysis(report, source, selection);
    }

    /** Convenience overload for callers that only need the report. */
    public AnalysisReport analyze(MultipartFile stdoutFile, MultipartFile stderrFile) throws IOException {
        return analyzeUploads(stdoutFile, stderrFile).report();
    }

    // ---- Unit-based analysis ---------------------------------------------------------------

    private AnalysisReport analyzeUnits(List<ProcessingUnit> units, LogSource source, String filenameJobId,
                                        ProfileMatch match, JobIdentity identity, List<LogIssueDto> issues) {

        List<ProcessingUnit> running = units.stream().filter(unit -> !unit.completed()).toList();

        LocalDateTime overallStart = units.stream()
                .map(ProcessingUnit::start)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);

        // While anything is still running there is no meaningful end time yet.
        LocalDateTime overallEnd = running.isEmpty()
                ? units.stream()
                    .map(ProcessingUnit::end)
                    .filter(Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(null)
                : null;

        String overallStatus;
        if (units.stream().anyMatch(ProcessingUnit::failed)) {
            overallStatus = STATUS_FAILED;
        } else if (!running.isEmpty()) {
            overallStatus = STATUS_RUNNING;
        } else if (units.size() == 1 && units.get(0).status() != null
                && !isPlainSuccessToken(units.get(0).status())) {
            // Single-unit job with an extended status: mirror it so the report stays self-consistent
            // (e.g. FINISHED_OK_WARNINGS rather than the normalised SUCCESS).
            // Plain tokens (OK, SUCCESS, SUCCEEDED, COMPLETED) are deliberately collapsed to SUCCESS
            // so that "STATUS : OK" in a batch-layer log doesn't appear as-is in the summary.
            overallStatus = units.get(0).status();
        } else {
            overallStatus = STATUS_SUCCESS;
        }

        CurrentProgressDto currentProgressDetail = describeCurrentUnit(running);

        // Error details are collected whatever the outcome. A job can log a stack trace and still
        // finish OK, and suppressing the detail because the status said SUCCESS hid exactly the
        // errors worth seeing. When the log is clean these come back null and nothing is shown.
        // The job id stays tied to a failure - it identifies which run to go and investigate.
        FailureInfo failure = collectFailureInfo(
                source, STATUS_FAILED.equals(overallStatus) ? filenameJobId : null);

        String failedUnitName = units.stream()
                .filter(ProcessingUnit::failed)
                .map(ProcessingUnit::name)
                .findFirst()
                .orElse(null);

        return new AnalysisReport(
                "layers",
                match.id(),
                match.confidence(),
                firstNonNull(identity.jobName(), getJobName(source)),
                firstNonNull(identity.dagName(), failure.dagName()),
                firstNonNull(identity.taskName(), failure.taskName()),
                overallStatus,
                formatTimestamp(overallStart),
                formatTimestamp(overallEnd),
                formatDurationBetween(overallStart, overallEnd),
                sumByKind(units, MetricDto.Kind.INPUT),
                sumByKind(units, MetricDto.Kind.ERROR),
                sumByKind(units, MetricDto.Kind.WARN),
                sumByKind(units, MetricDto.Kind.OK),
                sumByKind(units, MetricDto.Kind.REJECTED),
                formatUnits(units.stream().filter(ProcessingUnit::completed).toList()),
                formatUnits(running),
                buildCurrentProgressSummary(currentProgressDetail),
                currentProgressDetail,
                Located.textOf(failure.failedComponent()),
                failedUnitName,
                failure.jobId(),
                Located.textOf(failure.mainError()),
                Located.textOf(failure.rootCause()),
                Located.textOf(failure.stackTrace()),
                Located.textOf(failure.failedQuery()),
                getTalendComponents(source),
                issues,
                failure.locations()
        );
    }

    /** The running unit to report on: the last one reporting progress, else the last started. */
    private CurrentProgressDto describeCurrentUnit(List<ProcessingUnit> running) {

        if (running.isEmpty()) {
            return null;
        }

        ProcessingUnit current = running.stream()
                .filter(unit -> unit.progress() != null)
                .reduce((earlier, later) -> later)
                .orElseGet(() -> running.get(running.size() - 1));

        Integer remaining = (current.processed() != null && current.total() != null)
                ? current.total() - current.processed()
                : null;

        return new CurrentProgressDto(
                current.name(),
                formatTimestampOrNull(current.start()),
                current.processed(),
                current.total(),
                remaining,
                current.percent(),
                null);
    }

    /**
     * Totals are summed by metric role rather than by name, so a format counting rows and one
     * counting files or events both roll up into the same four headline figures.
     */
    private int sumByKind(List<ProcessingUnit> units, MetricDto.Kind kind) {

        long total = units.stream()
                .flatMap(unit -> unit.metrics().stream())
                .filter(metric -> kind.name().equals(metric.kind()))
                .mapToLong(MetricDto::value)
                .sum();

        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    private List<EtlLayerDto> formatUnits(List<ProcessingUnit> units) {
        return units.stream().map(this::formatUnit).toList();
    }

    private EtlLayerDto formatUnit(ProcessingUnit unit) {
        return new EtlLayerDto(
                unit.name(),
                unit.executionId(),
                formatTimestamp(unit.start()),
                formatTimestamp(unit.end()),
                formatDurationBetween(unit.start(), unit.end()),
                unit.metric(MetricDto.Kind.INPUT),
                unit.metric(MetricDto.Kind.ERROR),
                unit.metric(MetricDto.Kind.WARN),
                unit.metric(MetricDto.Kind.OK),
                unit.metric(MetricDto.Kind.REJECTED),
                unit.status() != null ? unit.status() : STATUS_IN_PROGRESS,
                unit.progress(),
                unit.metrics(),
                unit.targets()
        );
    }

    /** Human-readable one-liner built from a {@link CurrentProgressDto}, kept for CSV export. */
    private String buildCurrentProgressSummary(CurrentProgressDto detail) {

        if (detail == null) {
            return null;
        }

        String prefix = detail.name() != null ? detail.name() + ": " : "";

        if (detail.processed() != null && detail.total() != null) {
            String percent = detail.percent() != null ? " (" + detail.percent() + "%)" : "";
            return prefix + detail.processed() + "/" + detail.total() + percent;
        }

        if (detail.lastActivity() != null) {
            return prefix + detail.lastActivity();
        }

        return detail.name();
    }

    // ---- Whole-log analysis (no discrete units) -------------------------------------------

    private AnalysisReport analyzeWholeLog(LogSource source, String filenameJobId, ProfileMatch match,
                                           List<LogIssueDto> issues) {

        LocalDateTime[] range = getTimeRange(source);
        LocalDateTime start = range[0];
        LocalDateTime end = range[1];

        String status = getKeywordStatus(source);
        String jobName = getJobName(source);

        FailureInfo failure = collectFailureInfo(
                source, STATUS_FAILED.equals(status) ? filenameJobId : null);

        CurrentProgressDto currentProgressDetail = STATUS_RUNNING.equals(status)
                ? getFallbackProgressDetail(source, jobName, start)
                : null;

        // Count errors and warnings in a single pass; upgrade a plain SUCCESS when
        // warnings are present so callers do not need to re-scan the log to detect them.
        int[] errWarn = countErrWarn(source.allLines);
        if (STATUS_SUCCESS.equals(status) && errWarn[1] > 0) {
            status = STATUS_FINISHED_OK_WARNINGS;
        }

        return new AnalysisReport(
                "legacy",
                match.id(),
                match.confidence(),
                jobName,
                failure.dagName(),
                failure.taskName(),
                status,
                formatTimestamp(start),
                formatTimestamp(end),
                formatDurationBetween(start, end),
                0,
                errWarn[0],
                errWarn[1],
                0,
                0,
                List.of(),
                List.of(),
                buildCurrentProgressSummary(currentProgressDetail),
                currentProgressDetail,
                Located.textOf(failure.failedComponent()),
                null,
                failure.jobId(),
                Located.textOf(failure.mainError()),
                Located.textOf(failure.rootCause()),
                Located.textOf(failure.stackTrace()),
                Located.textOf(failure.failedQuery()),
                getTalendComponents(source),
                issues,
                failure.locations()
        );
    }

    /**
     * Structured "Items processed: X/Y --&gt; Z%" line if present, else the last non-blank
     * log line, so a RUNNING report with no discrete units still shows live activity.
     */
    private CurrentProgressDto getFallbackProgressDetail(LogSource source, String jobName, LocalDateTime start) {

        String name = (jobName != null && !UNKNOWN_JOB_NAME.equals(jobName)) ? jobName : null;
        String startTime = formatTimestampOrNull(start);

        Parsing.Progress progress = Parsing.lastProgress(source.allText);
        if (progress != null) {
            return new CurrentProgressDto(
                    name,
                    startTime,
                    progress.processed(),
                    progress.total(),
                    progress.total() - progress.processed(),
                    progress.percent(),
                    null);
        }

        String lastActivity = findLastNonBlankLine(source.allLines);

        if (name == null && startTime == null && lastActivity == null) {
            return null;
        }

        return new CurrentProgressDto(name, startTime, null, null, null, null, lastActivity);
    }

    private String findLastNonBlankLine(List<String> lines) {

        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i).trim();
            if (!line.isEmpty()) {
                return line.length() > MAX_LAST_ACTIVITY_LENGTH
                        ? line.substring(0, MAX_LAST_ACTIVITY_LENGTH) + "..."
                        : line;
            }
        }

        return null;
    }

    private String getKeywordStatus(LogSource source) {

        String text = source.allText;

        if (containsAnyIgnoreCase(text, LEGACY_FAILURE_MARKERS) || hasExitCode(text, EXIT_CODE_FAILURE)) {
            return STATUS_FAILED;
        }
        if (containsAnyIgnoreCase(text, LEGACY_SUCCESS_MARKERS) || hasExitCode(text, EXIT_CODE_SUCCESS)) {
            return STATUS_SUCCESS;
        }
        return STATUS_RUNNING;
    }

    /** "EXIT CODE.*n" has no literal prefix to optimise, so gate it on the cheap marker first. */
    private boolean hasExitCode(String text, Pattern pattern) {
        return Parsing.containsIgnoreCase(text, EXIT_CODE_MARKER) && pattern.matcher(text).find();
    }

    /**
     * Earliest and latest timestamp in the log. Lines carrying SQL mutations are skipped so
     * literal dates inside INSERT/UPDATE statements cannot widen the range; timestamps that
     * are not at the start of a line are only considered when nothing better was found.
     */
    private LocalDateTime[] getTimeRange(LogSource source) {

        List<LocalDateTime> timestamps = new ArrayList<>();
        List<String> withoutLeadingTimestamp = new ArrayList<>();

        for (String line : source.allLines) {

            if (SQL_MUTATION_PATTERN.matcher(line).find()) {
                continue;
            }

            Matcher matcher = LEGACY_TS_LEADING.matcher(line);
            if (matcher.find()) {
                addIfParsable(timestamps, matcher.group(1));
            } else {
                withoutLeadingTimestamp.add(line);
            }
        }

        if (timestamps.isEmpty()) {
            for (String line : withoutLeadingTimestamp) {
                Matcher matcher = LEGACY_TS_BROAD.matcher(line);
                while (matcher.find()) {
                    addIfParsable(timestamps, matcher.group(1));
                }
            }
        }

        return new LocalDateTime[]{
                timestamps.stream().min(Comparator.naturalOrder()).orElse(null),
                timestamps.stream().max(Comparator.naturalOrder()).orElse(null)
        };
    }

    private void addIfParsable(List<LocalDateTime> timestamps, String raw) {
        LocalDateTime timestamp = Parsing.parseTimestamp(raw);
        if (timestamp != null) {
            timestamps.add(timestamp);
        }
    }

    /** Counts error and warning lines in a single pass rather than two separate passes. */
    private int[] countErrWarn(List<String> lines) {
        int errors = 0, warnings = 0;
        for (String line : lines) {
            if (Parsing.containsIgnoreCase(line, "error")) {
                errors++;
            } else if (Parsing.containsIgnoreCase(line, "warning")) {
                warnings++;
            }
        }
        return new int[]{errors, warnings};
    }

    // ---- Failure details -----------------------------------------------------------------

    /**
     * A value taken from the log together with where it came from, so every extracted field in
     * the report can be checked against the raw file.
     */
    private record Located(String text, String source, int line) {

        /** {@code stderr:412} - the reference a reader scrolls to. */
        String location() {
            return source + ":" + line;
        }

        static String textOf(Located found) {
            return found != null ? found.text() : null;
        }

        static String locationOf(Located found) {
            return found != null ? found.location() : null;
        }
    }

    /** Everything the report says about a failure, gathered in one place for both analysis paths. */
    private record FailureInfo(
            String jobId,
            String dagName,
            String taskName,
            Located failedComponent,
            Located mainError,
            Located rootCause,
            Located stackTrace,
            Located failedQuery
    ) {
        static final FailureInfo NONE = new FailureInfo(null, null, null, null, null, null, null, null);

        SourceLocationsDto locations() {
            return this == NONE ? SourceLocationsDto.NONE : new SourceLocationsDto(
                    Located.locationOf(failedComponent),
                    Located.locationOf(mainError),
                    Located.locationOf(rootCause),
                    Located.locationOf(stackTrace),
                    Located.locationOf(failedQuery));
        }
    }

    /**
     * Collects all failure details in a single pass through each stream (stderr-first), rather
     * than making 5+ separate passes. Previously each of the five detail fields independently
     * scanned both streams; this version short-circuits once all fields are populated.
     *
     * <p>mainError still prefers SQLException over any other Exception — that preference is
     * preserved by tracking a separate sqlException candidate.
     */
    private FailureInfo collectFailureInfo(LogSource source, String filenameJobId) {

        String dagName = Parsing.findGroup(DAG_NAME_PATTERN, source.allText).orElse(null);
        String taskName = Parsing.findGroup(TASK_NAME_PATTERN, source.allText).orElse(null);

        Located failedComponent = null;
        Located sqlException = null;
        Located anyException = null;
        Located causedBy = null;
        Located errorColon = null;
        Located stackTrace = null;
        Located failedQuery = null;

        // Iterate stderr first (failure details land there most often), then stdout.
        for (int stream = 0; stream < 2; stream++) {
            List<String> lines = stream == 0 ? source.stderrLines : source.stdoutLines;
            String srcName = stream == 0 ? LogSource.STDERR : LogSource.STDOUT;

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);

                if (failedComponent == null && EXCEPTION_COMPONENT_PATTERN.matcher(line).find()) {
                    Matcher m = EXCEPTION_COMPONENT_PATTERN.matcher(line);
                    m.find();
                    failedComponent = new Located(m.group(1), srcName, i + 1);
                }

                if (sqlException == null && line.contains("SQLException")) {
                    sqlException = new Located(line.trim(), srcName, i + 1);
                }

                if (anyException == null && line.contains("Exception")) {
                    anyException = new Located(line.trim(), srcName, i + 1);
                }

                if (causedBy == null && Parsing.containsIgnoreCase(line, "caused by:")) {
                    causedBy = new Located(line.trim(), srcName, i + 1);
                }

                if (errorColon == null && line.contains("ERROR:")) {
                    errorColon = new Located(line.trim(), srcName, i + 1);
                }

                if (stackTrace == null
                        && (line.contains("Exception") || Parsing.containsIgnoreCase(line, "caused by:"))) {
                    stackTrace = new Located(collectBlock(lines, i, MAX_STACK_TRACE_LINES), srcName, i + 1);
                }

                if (failedQuery == null && QUERY_EXEC_PATTERN.matcher(line).find()) {
                    failedQuery = new Located(collectBlock(lines, i, MAX_QUERY_LINES), srcName, i + 1);
                }

                // All fields found — stop scanning this stream.
                if (failedComponent != null && sqlException != null && causedBy != null
                        && stackTrace != null && failedQuery != null) {
                    break;
                }
            }

            // All fields found across both streams — no need to check stdout.
            if (failedComponent != null && (sqlException != null || anyException != null)
                    && (causedBy != null || errorColon != null)
                    && stackTrace != null && failedQuery != null) {
                break;
            }
        }

        Located mainError = sqlException != null ? sqlException : anyException;
        Located rootCause = causedBy != null ? causedBy : errorColon;

        return new FailureInfo(filenameJobId, dagName, taskName,
                failedComponent, mainError, rootCause, stackTrace, failedQuery);
    }

    /** Trimmed lines from {@code startIndex} up to the first blank line, capped at {@code maxLines}. */
    private String collectBlock(List<String> lines, int startIndex, int maxLines) {

        StringBuilder block = new StringBuilder();
        int collected = 0;

        for (int i = startIndex; i < lines.size() && collected < maxLines; i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                if (collected == 0) {
                    continue;
                }
                break;
            }
            if (collected > 0) {
                block.append('\n');
            }
            block.append(line.trim());
            collected++;
        }

        return block.toString();
    }

    // ---- Shared helpers --------------------------------------------------------------------

    /**
     * Job ID is derived from the uploaded file names (e.g. stdout_&lt;jobId&gt;.txt), not from log
     * content, since log content carries per-unit execution ids instead.
     */
    private String extractJobId(MultipartFile stdoutFile, MultipartFile stderrFile) {
        String jobId = extractJobIdFromFilename(stdoutFile);
        return jobId != null ? jobId : extractJobIdFromFilename(stderrFile);
    }

    private String extractJobIdFromFilename(MultipartFile file) {

        if (file == null) {
            return null;
        }

        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            return null;
        }

        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String base = slash >= 0 ? filename.substring(slash + 1) : filename;

        int dot = base.lastIndexOf('.');
        String noExt = dot > 0 ? base.substring(0, dot) : base;

        Matcher prefixMatcher = JOB_ID_PREFIX_PATTERN.matcher(noExt);
        if (prefixMatcher.matches()) {
            return prefixMatcher.group(1);
        }

        Matcher suffixMatcher = JOB_ID_SUFFIX_PATTERN.matcher(noExt);
        if (suffixMatcher.matches()) {
            return suffixMatcher.group(1);
        }

        return noExt;
    }

    private String getJobName(LogSource source) {

        for (String text : new String[]{source.stdoutText, source.stderrText}) {
            for (GuardedPattern candidate : JOB_NAME_PATTERNS) {
                Optional<String> match = candidate.firstGroup(text);
                if (match.isPresent()) {
                    return match.get();
                }
            }
        }

        return UNKNOWN_JOB_NAME;
    }

    private List<String> getTalendComponents(LogSource source) {

        Set<String> components = new TreeSet<>();

        Matcher matcher = TALEND_COMPONENT_PATTERN.matcher(source.allText);
        while (matcher.find()) {
            components.add(matcher.group(1));
        }

        return List.copyOf(components);
    }

    private static final Set<String> PLAIN_SUCCESS_TOKENS =
            Set.of("OK", "SUCCESS", "SUCCEEDED", "COMPLETED", "FINISHED_OK");

    /**
     * True when the status is a plain success token that should be normalised to "SUCCESS".
     * Includes the {@code OK_} prefix convention used by BatchLayerProfile
     * (e.g. {@code OK_WITH_WARNINGS} maps to SUCCESS rather than being surfaced as-is).
     */
    private boolean isPlainSuccessToken(String status) {
        String normalized = status.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_]", "");
        return PLAIN_SUCCESS_TOKENS.contains(normalized) || normalized.startsWith("OK_");
    }

    private String firstNonNull(String preferred, String fallback) {
        return preferred != null ? preferred : fallback;
    }

    private String formatTimestamp(LocalDateTime timestamp) {
        return timestamp != null ? timestamp.toString() : NOT_AVAILABLE;
    }

    private String formatTimestampOrNull(LocalDateTime timestamp) {
        return timestamp != null ? timestamp.toString() : null;
    }

    private String formatDurationBetween(LocalDateTime start, LocalDateTime end) {
        return (start != null && end != null)
                ? formatDuration(Duration.between(start, end))
                : NOT_AVAILABLE;
    }

    /** {@code H:MM:SS}; a negative span (end before start) keeps a single leading minus sign. */
    private String formatDuration(Duration duration) {

        long totalSeconds = duration.getSeconds();
        String sign = totalSeconds < 0 ? "-" : "";
        long seconds = Math.abs(totalSeconds);

        return String.format("%s%d:%02d:%02d", sign, seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    private static boolean containsAnyIgnoreCase(String text, String[] needles) {
        for (String needle : needles) {
            if (Parsing.containsIgnoreCase(text, needle)) {
                return true;
            }
        }
        return false;
    }
}
