package com.loganalyzer.service.issue;

import com.loganalyzer.model.LogIssueDto;
import com.loganalyzer.service.LogSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class IssueClassifierTest {

    private final IssueClassifier classifier = new IssueClassifier();

    // ---- Classification --------------------------------------------------------------------

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', value = {
            "ERROR: null value in column \"client_id\" violates not-null constraint | DATA_QUALITY",
            "ERROR: duplicate key value violates unique constraint \"pk_client\"    | DATA_QUALITY",
            "ERROR: invalid input syntax for type integer: \"abc\"                  | DATA_QUALITY",
            "ERROR java.net.ConnectException: Connection refused                    | CONNECTIVITY",
            "ERROR: password authentication failed for user \"etl\"                 | CONNECTIVITY",
            "ERROR java.lang.OutOfMemoryError: Java heap space                      | RESOURCE",
            "ERROR: no space left on device                                         | RESOURCE",
            "ERROR: permission denied for table core.client                         | PERMISSION",
            "ERROR java.io.FileNotFoundException: /data/in.csv                      | SOURCE_DATA",
            "ERROR: could not resolve placeholder 'db.url'                          | CONFIG"
    })
    @DisplayName("recognises the cause so the report can say who fixes it")
    void classifiesByCause(String line, String expectedCategory) throws IOException {

        List<LogIssueDto> issues = fromStdout(line);

        assertThat(issues).singleElement()
                .extracting(LogIssueDto::category, LogIssueDto::severity)
                .containsExactly(expectedCategory, "ERROR");
    }

    @Test
    @DisplayName("thousands of repeats of one failure collapse into a single counted issue")
    void foldsRepeatsIntoOneIssue() throws IOException {

        String log = IntStream.rangeClosed(1, 4000)
                .mapToObj(i -> "2026-08-01 22:41:%02d ERROR: null value in column \"client_id\" at row %d"
                        .formatted(i % 60, i))
                .collect(Collectors.joining("\n"));

        List<LogIssueDto> issues = fromStdout(log);

        assertThat(issues).singleElement()
                .extracting(LogIssueDto::category, LogIssueDto::occurrences)
                .containsExactly("DATA_QUALITY", 4000);
    }

    @Test
    @DisplayName("different causes stay separate and the most frequent leads")
    void keepsDistinctCausesApartAndRanksThem() throws IOException {

        String log = "ERROR java.net.ConnectException: Connection refused\n"
                + IntStream.range(0, 5)
                        .mapToObj(i -> "ERROR: null value in column \"client_id\" row " + i)
                        .collect(Collectors.joining("\n"));

        List<LogIssueDto> issues = fromStdout(log);

        assertThat(issues).hasSize(2);
        assertThat(issues.get(0).category()).isEqualTo("DATA_QUALITY");
        assertThat(issues.get(0).occurrences()).isEqualTo(5);
        assertThat(issues.get(1).category()).isEqualTo("CONNECTIVITY");
    }

    @Test
    @DisplayName("the offending table and component are pulled out of the line")
    void extractsTargetAndComponent() throws IOException {

        List<LogIssueDto> issues = fromStdout(
                "ERROR tPostgresqlOutput_3 - null value in column \"id\" of relation \"core.client\"");

        assertThat(issues).singleElement()
                .extracting(LogIssueDto::component, LogIssueDto::target)
                .containsExactly("tPostgresqlOutput_3", "core.client");
    }

    @Test
    @DisplayName("first and last occurrence are tracked so you can see when it started")
    void tracksFirstAndLastSeen() throws IOException {

        List<LogIssueDto> issues = fromStdout("""
                2026-08-01 22:41:03 ERROR: connection refused
                2026-08-01 22:45:17 ERROR: connection refused""");

        assertThat(issues).singleElement()
                .extracting(LogIssueDto::firstSeen, LogIssueDto::lastSeen)
                .containsExactly("2026-08-01 22:41:03", "2026-08-01 22:45:17");
    }

    @Test
    @DisplayName("counters and healthy lines are not mistaken for problems")
    void ignoresNoise() throws IOException {

        List<LogIssueDto> issues = fromStdout("""
                - Error : 0
                error_flag=false
                Raw data : 1000
                2026-08-01 22:00:04 INFO  loading complete""");

        assertThat(issues).isEmpty();
    }

    @Test
    @DisplayName("stack frames belong to their exception, not to a list of separate issues")
    void doesNotTreatStackFramesAsIssues() throws IOException {

        List<LogIssueDto> issues = fromStdout("""
                java.sql.SQLException: connection refused
                \tat org.postgresql.core.QueryExecutor.receive(QueryExecutor.java:2725)
                \tat etl.core.Loader.run(Loader.java:812)""");

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).category()).isEqualTo("CONNECTIVITY");
    }

    @Test
    @DisplayName("a warning with no recognisable cause is noise, an error is not")
    void keepsUnknownErrorsButDropsUnknownWarnings() throws IOException {

        List<LogIssueDto> issues = fromStdout("""
                WARNING something mildly unusual happened
                ERROR something went badly wrong""");

        assertThat(issues).singleElement()
                .extracting(LogIssueDto::category, LogIssueDto::severity)
                .containsExactly("UNKNOWN", "ERROR");
    }

    // ---- Provenance ------------------------------------------------------------------------

    @Nested
    @DisplayName("Every issue says where to look in the raw log")
    class Provenance {

        @Test
        @DisplayName("an issue in stdout reports its stdout line number")
        void reportsStdoutLineNumber() throws IOException {

            List<LogIssueDto> issues = fromStdout("""
                    INFO starting
                    INFO loading
                    ERROR: connection refused""");

            assertThat(issues).singleElement()
                    .extracting(LogIssueDto::source, LogIssueDto::firstLine, LogIssueDto::location)
                    .containsExactly("stdout", 3, "stdout:3");
        }

        @Test
        @DisplayName("a stderr line number counts from the top of stderr, not of the combined log")
        void numbersStderrFromItsOwnStart() throws IOException {

            LogSource source = source("""
                    INFO starting
                    INFO loading
                    INFO still going
                    INFO nearly there""", """
                    INFO stderr header
                    ERROR: permission denied for table core.client""");

            List<LogIssueDto> issues = classifier.classify(source, null);

            assertThat(issues).singleElement()
                    .extracting(LogIssueDto::source, LogIssueDto::firstLine, LogIssueDto::location)
                    .as("stdout has 4 lines, but this is stderr line 2 - not line 6")
                    .containsExactly("stderr", 2, "stderr:2");
        }

        @Test
        @DisplayName("a repeated problem reports the span from first to last occurrence")
        void reportsFirstAndLastLineOfARepeat() throws IOException {

            List<LogIssueDto> issues = fromStdout("""
                    INFO starting
                    ERROR: null value in column "a"
                    INFO carrying on
                    ERROR: null value in column "b"
                    INFO done""");

            assertThat(issues).singleElement()
                    .extracting(LogIssueDto::occurrences, LogIssueDto::firstLine, LogIssueDto::lastLine)
                    .containsExactly(2, 2, 4);
        }

        @Test
        @DisplayName("issues from both uploads each keep their own file and numbering")
        void keepsBothStreamsApart() throws IOException {

            LogSource source = source("""
                    INFO starting
                    ERROR java.lang.OutOfMemoryError: Java heap space""", """
                    ERROR: permission denied for table core.client""");

            List<LogIssueDto> issues = classifier.classify(source, null);

            assertThat(issues)
                    .extracting(LogIssueDto::category, LogIssueDto::location)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple("RESOURCE", "stdout:2"),
                            org.assertj.core.groups.Tuple.tuple("PERMISSION", "stderr:1"));
        }
    }

    // ---- Helpers ---------------------------------------------------------------------------

    private List<LogIssueDto> fromStdout(String content) throws IOException {
        return classifier.classify(source(content, null), null);
    }

    private LogSource source(String stdout, String stderr) throws IOException {
        return LogSource.read(part("stdout", stdout), part("stderr", stderr));
    }

    private MockMultipartFile part(String name, String content) {
        return content == null
                ? null
                : new MockMultipartFile(name, name + "_job.txt", "text/plain",
                        content.getBytes(StandardCharsets.UTF_8));
    }
}
