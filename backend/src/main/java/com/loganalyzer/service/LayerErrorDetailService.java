package com.loganalyzer.service;

import com.loganalyzer.model.LayerErrorDetailDto;
import com.loganalyzer.model.LayerErrorIssueDto;
import com.loganalyzer.model.LayerErrorRecordDto;
import com.loganalyzer.service.profile.Parsing;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a single per-layer detail file: the standalone stdout a batch layer writes for just
 * itself, attached from the ETL Layer Errors tab to see which records failed and why.
 *
 * <p>The combined multi-layer stdout collapses a layer's failures into one count in its
 * PROCESSING SUMMARY block. This file is where that count comes from: a "TIMESTAMP --- KEY=value"
 * line per record, identified by whatever field that layer uses (IDMOV, IDPARTYLOCK, TABLE, ...),
 * followed by one or more "E|W - Code --&gt; message" lines for what went wrong with it.
 */
@Service
public class LayerErrorDetailService {

    private static final Pattern IDEXECUTION_PATTERN = Pattern.compile("IDEXECUTION\\s*:\\s*(\\d+)");
    private static final Pattern BATCH_PATTERN = Pattern.compile("BATCH\\s*:\\s*(\\S+)");
    private static final Pattern RAW_PATTERN = Pattern.compile("Raw data\\s*:\\s*(\\d+)");
    private static final Pattern ERROR_PATTERN = Pattern.compile("-\\s*Error\\s*:\\s*(\\d+)");
    private static final Pattern WARNING_PATTERN = Pattern.compile("-\\s*Warning\\s*:\\s*(\\d+)");
    private static final Pattern OK_PATTERN = Pattern.compile("-\\s*OK\\s*:\\s*(\\d+)");
    private static final Pattern STATUS_PATTERN = Pattern.compile("\\bSTATUS\\s*:\\s*(\\S+)");

    private static final Pattern RECORD_HEADER =
            Pattern.compile("^(\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}:\\d{2}) --- ([A-Za-z_]+)=(\\S+)$");
    private static final Pattern ISSUE_LINE = Pattern.compile("^(E|W) - (\\S+) --> (.+)$");

    public LayerErrorDetailDto parse(MultipartFile file) throws IOException {

        List<String> lines = readLines(file);
        String text = String.join("\n", lines);

        return new LayerErrorDetailDto(
                Parsing.findGroup(IDEXECUTION_PATTERN, text).orElse(null),
                Parsing.findGroup(BATCH_PATTERN, text).orElse(null),
                Parsing.findInt(RAW_PATTERN, text),
                Parsing.findInt(ERROR_PATTERN, text),
                Parsing.findInt(WARNING_PATTERN, text),
                Parsing.findInt(OK_PATTERN, text),
                Parsing.findGroup(STATUS_PATTERN, text).orElse(null),
                parseRecords(lines));
    }

    /**
     * One pass: a header line opens a record, every issue line up to the next header (or blank
     * run) belongs to it. A header with no issue lines still appears - the log declared it, and
     * dropping it would leave a count the record list cannot account for.
     */
    private List<LayerErrorRecordDto> parseRecords(List<String> lines) {

        List<LayerErrorRecordDto> records = new ArrayList<>();

        String timestamp = null, key = null, id = null;
        List<LayerErrorIssueDto> issues = new ArrayList<>();

        for (String line : lines) {

            Matcher header = RECORD_HEADER.matcher(line);
            if (header.matches()) {
                if (key != null) {
                    records.add(new LayerErrorRecordDto(timestamp, key, id, List.copyOf(issues)));
                }
                timestamp = header.group(1);
                key = header.group(2);
                id = header.group(3);
                issues = new ArrayList<>();
                continue;
            }

            if (key == null) {
                continue;
            }

            Matcher issueLine = ISSUE_LINE.matcher(line.trim());
            if (issueLine.matches()) {
                issues.add(new LayerErrorIssueDto(
                        "E".equals(issueLine.group(1)) ? "ERROR" : "WARNING",
                        issueLine.group(2),
                        issueLine.group(3)));
            }
        }

        if (key != null) {
            records.add(new LayerErrorRecordDto(timestamp, key, id, List.copyOf(issues)));
        }

        return records;
    }

    private List<String> readLines(MultipartFile file) throws IOException {

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
