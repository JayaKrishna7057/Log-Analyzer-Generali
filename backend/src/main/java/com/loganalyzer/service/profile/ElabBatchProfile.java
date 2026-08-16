package com.loganalyzer.service.profile;

import com.loganalyzer.model.MetricDto;
import com.loganalyzer.model.RecordStatusDto;
import com.loganalyzer.service.LogSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Elaboration-batch logs produced by the GSP_Tirea / GEL-batch family.
 *
 * <p>Each job run produces a single processing unit. The final record counts live in the
 * {@code SUMMARY START … SUMMARY END} block; the overall status comes from the {@code ESTADO:} or
 * {@code STATE:} marker at the very end of stdout.
 *
 * <p>Jobs that log errors without a SUMMARY block (e.g. GEL tasks that abort early) return an
 * empty unit list so the service falls back to whole-log analysis.
 */
public class ElabBatchProfile implements LogProfile {

    private static final Pattern BATCH_VERSION =
            Pattern.compile("^BATCH_VERSION:\\s*(\\S+)", Pattern.MULTILINE);
    private static final Pattern RUN_ID =
            Pattern.compile("^RunId:\\s*(\\S+)", Pattern.MULTILINE);
    private static final Pattern SUMMARY_START = Pattern.compile("SUMMARY START");
    private static final Pattern ESTADO_PATTERN =
            Pattern.compile("\\b(?:ESTADO|STATE):\\s*(\\S+)");

    private static final Pattern JOB_ID_PATTERN =
            Pattern.compile("AZ_BATCH_JOB_ID\\s*[=:]\\s*(\\S+)");

    // @t@[dd/MM/yyyy HH:mm:ss] = job start;  @T@[...] = job end (uppercase T only)
    private static final Pattern START_TIME =
            Pattern.compile("@t@\\[([\\d/]+ [\\d:]+)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern END_TIME =
            Pattern.compile("@T@\\[([\\d/]+ [\\d:]+)\\]");

    // ---- SUMMARY block metrics -----------------------------------------------------------
    //
    // The block splits its figures across a base line and qualified variants of it:
    //
    //     Record Succesful                      : 8
    //     Record Succesful WithoutMatch         : 0
    //     Record Succesful WithoutDocument      : 0
    //     Record Unchanged                      : 117
    //     Record Discarded NotToWork            : 0
    //     Record Discarded                      : 107
    //     Record PARTIALLY PROCESSED            : 0
    //     Record Unprocessed                    : -1
    //
    // Reading only the base lines silently dropped every qualified count, so a run whose successes
    // landed under a qualifier would under-report OK. The success family is therefore matched as a
    // family and summed, which also picks up qualifiers this analyzer has not seen.
    //
    // Counts can be negative: the batch derives "Unprocessed" as a remainder, so it goes below zero
    // when its own tallies overlap. Patterns accept a sign rather than matching the digits alone,
    // which would have turned -1 into 1.

    private static final Pattern RAW =
            Pattern.compile("Record to elaborate\\s*:\\s*(-?\\d+)");

    /** "Record Succesful" and every "Record Succesful <qualifier>" line. */
    private static final Pattern RECORD_SUCCESSFUL_FAMILY =
            Pattern.compile("Record Succe(?:s+)ful[^:\\n]*:\\s*(-?\\d+)");

    private static final Pattern RECORD_UNCHANGED =
            Pattern.compile("Record Unchanged\\s*:\\s*(-?\\d+)");

    // Matches "Record Discarded    : 107" but NOT "Record Discarded NotToWork : 0"
    // because \s*: stops at the first non-whitespace character (the 'N' in NotToWork).
    private static final Pattern RECORD_DISCARDED =
            Pattern.compile("Record Discarded\\s*:\\s*(-?\\d+)");

    // Deliberately excluded from work rather than rejected, so counted apart from KO: folding it in
    // would report records as failures that the batch never attempted.
    private static final Pattern RECORD_DISCARDED_NOT_TO_WORK =
            Pattern.compile("Record Discarded NotToWork\\s*:\\s*(-?\\d+)");

    private static final Pattern RECORD_PARTIALLY_PROCESSED =
            Pattern.compile("Record PARTIALLY PROCESSED\\s*:\\s*(-?\\d+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern RECORD_UNPROCESSED =
            Pattern.compile("Record Unprocessed\\s*:\\s*(-?\\d+)", Pattern.CASE_INSENSITIVE);

    // RECORD SCARTATO lines carry per-record KO reasons. Strip the IDDIALOGUEMSG envelope
    // so the actual cause collapses across repeats when fingerprinted.
    private static final Pattern SCARTATO_PATTERN =
            Pattern.compile("RECORD SCARTATO\\s*:(.+)$", Pattern.CASE_INSENSITIVE);

    // Per-record extraction: IDDIALOGUE blocks and their following STATUS line.
    private static final Pattern IDDIALOGUE_PATTERN = Pattern.compile(
            "IDDIALOGUE:\\s*(\\S+)\\s*-\\s*INTERNALKEY:\\s*(\\S+)\\s*-\\s*UNIQUECODE:\\s*(\\S+)");
    private static final Pattern STATUS_LINE_PATTERN =
            Pattern.compile("STATUS:\\s*(\\S+)");
    private static final Pattern BRACKET_ID_PATTERN =
            Pattern.compile("\\[(\\d+)\\]");

    private static final Set<String> SUCCESS_STATUSES =
            Set.of("FINISHED_OK", "OK", "SUCCESS", "SUCCEEDED", "COMPLETED");

    @Override
    public String id() {
        return "elab-batch";
    }

    @Override
    public String displayName() {
        return "Elaboration Batch log";
    }

    @Override
    public double detect(LogSource source) {
        String text = source.allText;

        // Both markers must be present; their combination is unique to this family.
        if (!BATCH_VERSION.matcher(text).find()) return 0;
        if (!RUN_ID.matcher(text).find()) return 0;

        return 0.6 + 0.4 * Parsing.signatureScore(text, SUMMARY_START, ESTADO_PATTERN);
    }

    /**
     * Returns a single unit when the SUMMARY block is present. Without a SUMMARY block the job
     * aborted before producing counts, and whole-log analysis is more useful than an empty unit.
     */
    @Override
    public List<ProcessingUnit> parse(LogSource source) {
        String text = source.allText;

        if (!SUMMARY_START.matcher(text).find()) {
            return List.of();
        }

        String jobName = Parsing.findGroup(JOB_ID_PATTERN, text)
                .orElseGet(() -> Parsing.findGroup(RUN_ID, text).orElse("ElabBatch"));

        ProcessingUnit.Builder unit = ProcessingUnit.named(jobName);

        Parsing.findGroup(RUN_ID, text).ifPresent(unit::executionId);

        unit.start(Parsing.findTimestamp(START_TIME, text));
        unit.end(Parsing.findTimestamp(END_TIME, text));

        unit.metric("raw", "Raw data", Parsing.findInt(RAW, text), MetricDto.Kind.INPUT);
        unit.metric("ko", "KO", Parsing.findInt(RECORD_DISCARDED, text), MetricDto.Kind.REJECTED);

        // Unchanged records were processed successfully, they simply needed no write.
        Integer successful = Parsing.sumInts(RECORD_SUCCESSFUL_FAMILY, text);
        Integer unchanged = Parsing.findInt(RECORD_UNCHANGED, text);
        Integer ok = (successful == null && unchanged == null)
                ? null
                : coalesce(successful) + coalesce(unchanged);
        unit.metric("ok", "OK", ok, MetricDto.Kind.OK);

        // Reported under their own names rather than folded into the headline figures, so the
        // summary stays reconcilable against "Record to elaborate" instead of quietly losing rows.
        unit.metric("discardedNotToWork", "Discarded (not to work)",
                Parsing.findInt(RECORD_DISCARDED_NOT_TO_WORK, text), MetricDto.Kind.SKIPPED);
        unit.metric("partiallyProcessed", "Partially processed",
                Parsing.findInt(RECORD_PARTIALLY_PROCESSED, text), MetricDto.Kind.OTHER);
        unit.metric("unprocessed", "Unprocessed",
                Parsing.findInt(RECORD_UNPROCESSED, text), MetricDto.Kind.OTHER);

        Parsing.findGroup(ESTADO_PATTERN, text)
                .ifPresent(status -> unit.status(status, !isSuccess(status)));

        return List.of(unit.build());
    }

    @Override
    public JobIdentity identify(LogSource source) {
        String jobId = Parsing.findGroup(JOB_ID_PATTERN, source.allText).orElse(null);
        return new JobIdentity(jobId, jobId, null);
    }

    /**
     * Extracts per-record outcomes from the log.
     *
     * <p>Each record is identified by an "IDDIALOGUE: X - INTERNALKEY: Y - UNIQUECODE: Z" line
     * followed immediately by "STATUS: OK" or "STATUS: KO". KO records are matched against
     * the RECORD SCARTATO entries to supply a rejection reason.
     */
    @Override
    public List<RecordStatusDto> parseRecords(LogSource source) {

        Map<String, String> koReasonByDialogueId = buildKoReasonMap(source.allLines);
        List<RecordStatusDto> records = new ArrayList<>();

        // One pass: an IDDIALOGUE line opens a record, the next STATUS line closes it, and the
        // following IDDIALOGUE (or the end of the log) closes it regardless.
        //
        // A record whose block carries no STATUS is emitted with a null status rather than being
        // skipped. Dropping it lost a record the log had declared - it was counted in "Record to
        // elaborate" but absent from the list, so the totals stopped adding up with nothing to say
        // why. A record the batch never reported on is exactly what "Unprocessed" counts, and it
        // has to remain visible.
        //
        // Bounding the search by the next record rather than by a fixed number of lines means a
        // block with extra lines in it is still read correctly, and a status can never be taken
        // from the record that follows.
        String[] open = null;

        for (String rawLine : source.allLines) {

            Matcher dm = IDDIALOGUE_PATTERN.matcher(rawLine);
            if (dm.find()) {
                if (open != null) {
                    records.add(toRecord(open, null, koReasonByDialogueId));
                }
                open = new String[]{dm.group(1), dm.group(2), dm.group(3)};
                continue;
            }

            if (open == null) {
                continue;
            }

            Matcher sm = STATUS_LINE_PATTERN.matcher(rawLine.trim());
            if (sm.matches()) {
                records.add(toRecord(open, sm.group(1).toUpperCase(Locale.ROOT), koReasonByDialogueId));
                open = null;
            }
        }

        if (open != null) {
            records.add(toRecord(open, null, koReasonByDialogueId));
        }

        return records;
    }

    /** {@code identity} is the dialogue id, internal key and unique code, in that order. */
    private RecordStatusDto toRecord(String[] identity, String status, Map<String, String> koReasons) {
        // A rejection reason is attached whenever the log recorded one for this record, including
        // when no status line was printed - the reason is often why it never got one.
        String koReason = koReasons.get(identity[0]);
        return new RecordStatusDto(identity[0], identity[1], identity[2], status, koReason);
    }

    /**
     * Builds a map from dialogue ID to KO reason by scanning for RECORD SCARTATO lines.
     * The reason is the last " : "-delimited segment, which strips the repeated envelope prefix.
     */
    private Map<String, String> buildKoReasonMap(List<String> lines) {
        Map<String, String> reasons = new LinkedHashMap<>();

        for (String line : lines) {
            if (!Parsing.containsIgnoreCase(line, "SCARTATO")) {
                continue;
            }

            Matcher idMatcher = BRACKET_ID_PATTERN.matcher(line);
            if (!idMatcher.find()) {
                continue;
            }
            String dialogueId = idMatcher.group(1);

            int lastSep = line.lastIndexOf(" : ");
            if (lastSep < 0) {
                continue;
            }
            String reason = line.substring(lastSep + 3).trim();

            if (!reason.isEmpty()) {
                reasons.putIfAbsent(dialogueId, reason);
            }
        }

        return reasons;
    }

    /**
     * Extracts the meaningful error cause from RECORD SCARTATO lines by taking only the last
     * colon-delimited segment (the actual message, not the repeated IDDIALOGUEMSG envelope).
     * All other lines are returned as-is so the default issue filters apply.
     */
    @Override
    public String issueText(String line) {
        // Pre-filter: the vast majority of lines are not RECORD SCARTATO lines.
        // Checking for the literal before invoking the regex eliminates the regex cost for ~99%
        // of calls on a typical elaboration-batch log.
        if (!Parsing.containsIgnoreCase(line, "scartato")) {
            return line;
        }
        Matcher m = SCARTATO_PATTERN.matcher(line.trim());
        if (m.find()) {
            String payload = m.group(1).trim();
            int lastSep = payload.lastIndexOf(" : ");
            String msg = lastSep >= 0 ? payload.substring(lastSep + 3).trim() : payload;
            return "ERROR: " + msg;
        }
        return line;
    }

    private int coalesce(Integer value) {
        return value != null ? value : 0;
    }

    private boolean isSuccess(String status) {
        String normalized = status.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "");
        return SUCCESS_STATUSES.contains(normalized) || normalized.startsWith("FINISHED_OK");
    }
}
