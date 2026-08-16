package com.loganalyzer.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The uploaded logs, read once into every shape the analysis needs.
 *
 * <p>Uploads are allowed up to 50 MB per file, so re-joining the lines inside each
 * helper (as the analysis used to do) copied the whole log once per regex. Building
 * the line lists and the joined text a single time keeps that cost linear.
 */
public final class LogSource {

    private static final String LINE_SEPARATOR = "\n";

    /** Lines of the stdout upload, in file order; empty when no stdout file was sent. */
    public final List<String> stdoutLines;

    /** Lines of the stderr upload, in file order; empty when no stderr file was sent. */
    public final List<String> stderrLines;

    /** stdout then stderr - the reading order for general scans. */
    public final List<String> allLines;

    /** stderr then stdout - failure details are far more likely to sit in stderr. */
    public final List<String> failureLines;

    public final String stdoutText;
    public final String stderrText;

    /** {@link #allLines} joined with newlines, for the regexes that span lines. */
    public final String allText;

    private LogSource(List<String> stdoutLines, List<String> stderrLines) {
        this.stdoutLines = Collections.unmodifiableList(stdoutLines);
        this.stderrLines = Collections.unmodifiableList(stderrLines);
        this.allLines = concat(stdoutLines, stderrLines);
        this.failureLines = concat(stderrLines, stdoutLines);
        this.stdoutText = String.join(LINE_SEPARATOR, stdoutLines);
        this.stderrText = String.join(LINE_SEPARATOR, stderrLines);
        this.allText = joinStreams();
    }

    public static final String STDOUT = "stdout";
    public static final String STDERR = "stderr";

    public static LogSource read(MultipartFile stdoutFile, MultipartFile stderrFile) throws IOException {
        return new LogSource(readLines(stdoutFile), readLines(stderrFile));
    }

    /**
     * Which upload the given {@link #allLines} index came from. {@code allLines} is stdout
     * followed by stderr, so the boundary is simply the length of the stdout part.
     */
    public String sourceOf(int allLinesIndex) {
        return allLinesIndex < stdoutLines.size() ? STDOUT : STDERR;
    }

    /** The 1-based line number within that file, which is what a reader will scroll to. */
    public int lineOf(int allLinesIndex) {
        return allLinesIndex < stdoutLines.size()
                ? allLinesIndex + 1
                : allLinesIndex - stdoutLines.size() + 1;
    }

    private String joinStreams() {
        if (stdoutLines.isEmpty()) {
            return stderrText;
        }
        if (stderrLines.isEmpty()) {
            return stdoutText;
        }
        return stdoutText + LINE_SEPARATOR + stderrText;
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> result = new ArrayList<>(first.size() + second.size());
        result.addAll(first);
        result.addAll(second);
        return Collections.unmodifiableList(result);
    }

    private static List<String> readLines(MultipartFile file) throws IOException {

        if (file == null || file.isEmpty()) {
            return List.of();
        }

        List<String> lines = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }

        return lines;
    }
}
