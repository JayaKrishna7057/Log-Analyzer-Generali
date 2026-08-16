package com.loganalyzer.service.issue;

import com.loganalyzer.model.LogIssueDto;
import com.loganalyzer.model.LogIssueDto.ContextLine;
import com.loganalyzer.service.LogSource;
import com.loganalyzer.service.profile.LogProfile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns raw log lines into a short list of distinct problems.
 *
 * <p>The work that matters here is folding repetitions together: every line is reduced to a
 * fingerprint with its variable parts removed, and lines sharing a fingerprint become one issue
 * carrying an occurrence count. Thousands of rejected rows collapse into a single actionable row.
 */
public class IssueClassifier {

    /** Log levels are conventionally upper case, which separates a real level from the word "error". */
    private static final Pattern SEVERITY_PATTERN =
            Pattern.compile("\\b(FATAL|SEVERE|ERROR|WARNING|WARN)\\b");

    private static final Pattern STACK_FRAME_PATTERN =
            Pattern.compile("^\\s*at\\s+\\S+\\(.*\\)\\s*$");

    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}"
                    + "|\\d{2}/\\d{2}/\\d{4}\\s+\\d{2}:\\d{2}:\\d{2}"
                    + "|\\d{2}/\\d{2}/\\d{2}\\s+\\d{2}:\\d{2}:\\d{2})");

    /** The same layouts as {@link #TIMESTAMP_PATTERN}, anchored, with optional millis and offset. */
    private static final Pattern LEADING_TIMESTAMP_PATTERN = Pattern.compile(
            "^\\s*(?:\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}"
                    + "|\\d{2}/\\d{2}/\\d{4}\\s+\\d{2}:\\d{2}:\\d{2}"
                    + "|\\d{2}/\\d{2}/\\d{2}\\s+\\d{2}:\\d{2}:\\d{2})"
                    + "(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?\\s*");

    private static final Pattern COMPONENT_PATTERN = Pattern.compile("\\b(t[A-Za-z0-9]+_\\d+)\\b");

    private static final Pattern[] TARGET_PATTERNS = {
            Pattern.compile("relation\\s+\"([^\"]+)\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(?:insert\\s+into|update|delete\\s+from|copy)\\s+\"?([A-Za-z0-9_.]+)\"?",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\btable\\s+\"?([A-Za-z0-9_.]+)\"?", Pattern.CASE_INSENSITIVE)
    };

    // Fingerprinting: strip the parts that vary between otherwise identical failures.
    private static final Pattern UUID_PATTERN =
            Pattern.compile("\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED_PATTERN = Pattern.compile("\"[^\"]*\"|'[^']*'");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+\\b");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private static final int MAX_ISSUES = 50;
    private static final int MAX_MESSAGE_LENGTH = 300;

    /**
     * Walks the log by index so every issue can name the file and line it came from - the
     * reference someone needs to check the finding against the raw log.
     *
     * @param source  the uploaded logs
     * @param profile the matched dialect, which decides what text each line contributes
     * @return distinct issues, most frequent first, capped at {@value #MAX_ISSUES}
     */
    public List<LogIssueDto> classify(LogSource source, LogProfile profile) {

        Map<String, Aggregate> aggregates = new LinkedHashMap<>();
        List<String> lines = source.allLines;

        for (int i = 0; i < lines.size(); i++) {

            String raw = lines.get(i);

            // Cheap pre-screen: skip lines that cannot possibly be interesting before calling
            // profile.issueText() (which may run regexes) and the category classifiers.
            if (!mightBeInteresting(raw)) {
                continue;
            }

            String text = profile == null ? raw : profile.issueText(raw);
            if (text == null) {
                continue;
            }

            String trimmed = text.trim();
            if (trimmed.isEmpty() || STACK_FRAME_PATTERN.matcher(trimmed).matches()) {
                continue;
            }

            // Determine severity and category in a single pass — previously this ran
            // SEVERITY_PATTERN twice (once in isInteresting, once in severityOf) and
            // IssueCategory.classify() twice (once in isInteresting, once here).
            Matcher severityMatcher = SEVERITY_PATTERN.matcher(trimmed);
            boolean hasSeverityMarker = severityMatcher.find();
            boolean hasException = !hasSeverityMarker && trimmed.contains("Exception");

            String severity;
            if (hasSeverityMarker) {
                severity = severityMatcher.group(1).startsWith("WARN") ? "WARNING" : "ERROR";
            } else if (hasException) {
                severity = "ERROR";
            } else {
                severity = "WARNING";
            }

            IssueCategory category = IssueCategory.classify(trimmed);

            // An unremarkable warning with no recognisable cause is noise, not a finding.
            if (category == IssueCategory.UNKNOWN && !"ERROR".equals(severity)) {
                continue;
            }

            String key = severity + '|' + category + '|' + fingerprint(trimmed);
            String origin = source.sourceOf(i);
            int lineNumber = source.lineOf(i);
            aggregates.computeIfAbsent(key,
                            ignored -> new Aggregate(severity, category, trimmed, origin, lineNumber))
                    .add(trimmed, lineNumber);
        }

        return aggregates.values().stream()
                .sorted(Comparator.comparingInt((Aggregate a) -> a.occurrences).reversed())
                .limit(MAX_ISSUES)
                .map(a -> a.toDto(source))
                .toList();
    }

    /**
     * Cheap literal pre-screen applied before any regex work. Returns false for lines that
     * cannot possibly produce an issue — clean INFO/DEBUG lines in verbose batch logs often
     * account for 90 %+ of all lines and this guard eliminates the per-line regex overhead
     * for the vast majority of them.
     */
    private static boolean mightBeInteresting(String line) {
        return line.contains("ERROR") || line.contains("error")
                || line.contains("WARN") || line.contains("warn")
                || line.contains("FATAL") || line.contains("fatal")
                || line.contains("SEVERE")
                || line.contains("Exception") || line.contains("exception")
                || line.contains("refused") || line.contains("denied")
                || line.contains("permission") || line.contains("timeout")
                || line.contains("SCARTATO") || line.contains("scartato");
    }

    /** The message with its variable parts replaced, so repeats of one failure collapse together. */
    String fingerprint(String line) {

        String normalized = UUID_PATTERN.matcher(line).replaceAll("<uuid>");
        normalized = QUOTED_PATTERN.matcher(normalized).replaceAll("?");
        normalized = NUMBER_PATTERN.matcher(normalized).replaceAll("#");
        normalized = WHITESPACE_PATTERN.matcher(normalized).replaceAll(" ");

        return normalized.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Drops a timestamp sitting at the very start of a line. The timestamp is recorded separately
     * as first/last seen, so repeating it in the message would only crowd the column.
     */
    private static String stripLeadingTimestamp(String line) {

        Matcher matcher = LEADING_TIMESTAMP_PATTERN.matcher(line);
        return matcher.find() ? line.substring(matcher.end()).trim() : line;
    }

    private static String firstMatch(Pattern pattern, String line) {
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String findTarget(String line) {
        for (Pattern pattern : TARGET_PATTERNS) {
            String target = firstMatch(pattern, line);
            if (target != null) {
                return target;
            }
        }
        return null;
    }

    /** Mutable running total for one fingerprint. */
    private static final class Aggregate {

        private final String severity;
        private final IssueCategory category;
        private final String sample;
        private final String component;
        private final String target;
        private final String firstSeen;
        private final String source;
        private final int firstLine;

        private String lastSeen;
        private int lastLine;
        private int occurrences;

        private Aggregate(String severity, IssueCategory category, String sample,
                          String source, int firstLine) {
            this.severity = severity;
            this.category = category;
            String display = stripLeadingTimestamp(sample);
            this.sample = display.length() > MAX_MESSAGE_LENGTH
                    ? display.substring(0, MAX_MESSAGE_LENGTH) + "..."
                    : display;
            this.component = firstMatch(COMPONENT_PATTERN, sample);
            this.target = findTarget(sample);
            this.firstSeen = firstMatch(TIMESTAMP_PATTERN, sample);
            this.lastSeen = this.firstSeen;
            this.source = source;
            this.firstLine = firstLine;
            this.lastLine = firstLine;
        }

        private void add(String line, int lineNumber) {
            occurrences++;
            lastLine = lineNumber;
            String timestamp = firstMatch(TIMESTAMP_PATTERN, line);
            if (timestamp != null) {
                lastSeen = timestamp;
            }
        }

        private LogIssueDto toDto(LogSource logSource) {
            return new LogIssueDto(
                    severity, category.name(), sample, component, target,
                    occurrences, firstSeen, lastSeen, source, firstLine, lastLine,
                    buildContext(logSource));
        }

        /**
         * Collects up to 5 lines before and after the first occurrence within its source stream,
         * so the UI can show the error in the context of the surrounding log activity without
         * the reader having to open the raw file.
         */
        private List<ContextLine> buildContext(LogSource logSource) {
            List<String> stream = LogSource.STDERR.equals(source)
                    ? logSource.stderrLines
                    : logSource.stdoutLines;

            int center = firstLine - 1; // 0-based
            int from = Math.max(0, center - 4);
            int to = Math.min(stream.size(), center + 6);

            List<ContextLine> ctx = new ArrayList<>(to - from);
            for (int i = from; i < to; i++) {
                ctx.add(new ContextLine(i + 1, stream.get(i)));
            }
            return ctx;
        }
    }
}
