package com.loganalyzer.service.profile;

import com.loganalyzer.service.LogSource;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Talend job output: component exception lines, {@code tXxx_N} component ids, "Query exec"
 * markers and an EXIT CODE verdict.
 *
 * <p>This dialect has no block structure to split on, so it deliberately reports no units and
 * lets the analyzer's whole-log path derive status, time range and failure details. Recognising
 * it still matters: without this, the analyzer's own long-standing format would be reported as
 * "not recognised", and that warning must stay meaningful.
 */
public class TalendGenericProfile implements LogProfile {

    private static final Pattern EXCEPTION_IN_COMPONENT = Pattern.compile("Exception in component");
    private static final Pattern COMPONENT_ID = Pattern.compile("\\bt[A-Za-z0-9]+_\\d+\\b");
    private static final Pattern TALEND_WORD = Pattern.compile("talend", Pattern.CASE_INSENSITIVE);

    private static final Pattern QUERY_EXEC = Pattern.compile("Query\\s+exec", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXIT_CODE = Pattern.compile("EXIT CODE", Pattern.CASE_INSENSITIVE);
    private static final Pattern FATAL = Pattern.compile("\\[FATAL\\]", Pattern.CASE_INSENSITIVE);

    @Override
    public String id() {
        return "talend-job";
    }

    @Override
    public String displayName() {
        return "Talend job log";
    }

    @Override
    public double detect(LogSource source) {

        String text = source.allText;

        boolean looksTalend = EXCEPTION_IN_COMPONENT.matcher(text).find()
                || COMPONENT_ID.matcher(text).find()
                || TALEND_WORD.matcher(text).find();

        if (!looksTalend) {
            return 0;
        }

        // Kept just below the unit-producing dialects, so a log carrying both loses to the one
        // that can actually break the work down.
        return 0.5 + 0.25 * Parsing.signatureScore(text, QUERY_EXEC, EXIT_CODE, FATAL);
    }

    /** No block markers to split on - the analyzer's whole-log path handles this dialect. */
    @Override
    public List<ProcessingUnit> parse(LogSource source) {
        return List.of();
    }
}
