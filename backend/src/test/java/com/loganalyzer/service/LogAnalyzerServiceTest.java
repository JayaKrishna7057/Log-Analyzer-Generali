package com.loganalyzer.service;

import com.loganalyzer.model.AnalysisReport;
import com.loganalyzer.model.CurrentProgressDto;
import com.loganalyzer.model.EtlLayerDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LogAnalyzerServiceTest {

    private final LogAnalyzerService service =
            new LogAnalyzerService(new com.loganalyzer.service.profile.ProfileRegistry());

    // ---- Layer status ---------------------------------------------------------------------

    @Nested
    @DisplayName("Layer STATUS decides success or failure")
    class LayerStatus {

        @ParameterizedTest(name = "STATUS : {0} -> {1}")
        @CsvSource({
                "OK,                SUCCESS",
                "SUCCESS,           SUCCESS",
                "SUCCEEDED,         SUCCESS",
                "COMPLETED,         SUCCESS",
                "OK_WITH_WARNINGS,  SUCCESS",
                "KO,                FAILED",
                "NOT_OK,            FAILED",
                "ERROR,             FAILED",
                "ABORTED,           FAILED",
                "FAILED,            FAILED",
                "KO_TIMEOUT,        FAILED",
                "ERROR_DATA,        FAILED"
        })
        void mapsStatusTokenToOverallStatus(String status, String expected) throws IOException {
            AnalysisReport report = analyzeStdout(batchHeader() + layerBlock("CORE", status));

            assertThat(report.overallStatus()).isEqualTo(expected);
        }

        @Test
        @DisplayName("a status is only a success when it reads as one, not when it merely contains 'OK'")
        void doesNotTreatNegatedOkAsSuccess() throws IOException {
            AnalysisReport report = analyzeStdout(batchHeader() + layerBlock("CORE", "NOT_OK"));

            assertThat(report.overallStatus()).isEqualTo("FAILED");
            assertThat(report.failedLayerName()).isEqualTo("CORE");
        }

        @Test
        @DisplayName("one failed layer fails the whole run")
        void failsOverallWhenAnyLayerFailed() throws IOException {
            AnalysisReport report = analyzeStdout(
                    batchHeader() + layerBlock("STAGING", "OK") + layerBlock("CORE", "KO"));

            assertThat(report.overallStatus()).isEqualTo("FAILED");
            assertThat(report.failedLayerName()).isEqualTo("CORE");
            assertThat(report.completedEtlLayers()).hasSize(2);
        }

        @Test
        @DisplayName("a layer with no STATUS line is still in progress")
        void reportsRunningWhileALayerHasNoStatus() throws IOException {
            String log = batchHeader()
                    + layerBlock("STAGING", "OK")
                    + """
                      START CORE
                      IDEXECUTION : 10242
                      Starting time : 01/08/2026 22:18:52
                      """;

            AnalysisReport report = analyzeStdout(log);

            assertThat(report.overallStatus()).isEqualTo("RUNNING");
            assertThat(report.inProgressEtlLayers())
                    .singleElement()
                    .extracting(EtlLayerDto::layerName, EtlLayerDto::status)
                    .containsExactly("CORE", "IN PROGRESS");
            assertThat(report.overallEndTime())
                    .as("no end time while work is outstanding")
                    .isEqualTo("N/A");
        }
    }

    // ---- Numeric parsing ------------------------------------------------------------------

    @Nested
    @DisplayName("Counts")
    class Counts {

        @Test
        @DisplayName("a count too large for int is reported as absent instead of failing the request")
        void toleratesCountsWiderThanInt() throws IOException {
            String log = batchHeader() + """
                    START CORE
                    IDEXECUTION : 10242
                    Starting time : 01/08/2026 22:18:52
                    Raw data : 99999999999999
                    - Error : 0
                    - Warning : 0
                    - OK : 7
                    End time : 01/08/2026 22:41:37
                    STATUS : OK
                    """;

            AnalysisReport report = analyzeStdout(log);

            assertThat(report.completedEtlLayers()).singleElement()
                    .extracting(EtlLayerDto::rawData, EtlLayerDto::okCount)
                    .containsExactly(null, 7);
            assertThat(report.totalRawData()).isZero();
            assertThat(report.totalOk()).isEqualTo(7);
        }

        @Test
        @DisplayName("KO is read as a rejected-row count, per layer and as a total")
        void countsKoRows() throws IOException {

            String log = batchHeader() + """
                    START STAGING
                    IDEXECUTION : 1
                    Starting time : 01/08/2026 22:00:04
                    Raw data : 1000
                    - Error : 2
                    - Warning : 0
                    - OK : 988
                    - KO : 12
                    End time : 01/08/2026 22:18:51
                    STATUS : OK
                    START CORE
                    IDEXECUTION : 2
                    Starting time : 01/08/2026 22:18:52
                    Raw data : 500
                    - OK : 495
                    - KO : 5
                    End time : 01/08/2026 22:41:37
                    STATUS : OK
                    """;

            AnalysisReport report = analyzeStdout(log);

            assertThat(report.completedEtlLayers())
                    .extracting(EtlLayerDto::layerName, EtlLayerDto::okCount, EtlLayerDto::koCount)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("STAGING", 988, 12),
                            org.assertj.core.groups.Tuple.tuple("CORE", 495, 5));

            assertThat(report.totalKo()).isEqualTo(17);
            assertThat(report.totalOk()).isEqualTo(1483);
            assertThat(report.totalError())
                    .as("KO counts rejected rows; it must not be folded into the error count")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("KO is read with or without the leading dash")
        void acceptsKoWithoutADash() throws IOException {

            AnalysisReport report = analyzeStdout(batchHeader() + """
                    START CORE
                    IDEXECUTION : 1
                    Starting time : 01/08/2026 22:00:04
                    Raw data : 100
                    OK : 93
                    KO : 7
                    End time : 01/08/2026 22:10:00
                    STATUS : OK
                    """);

            assertThat(report.totalKo()).isEqualTo(7);
        }

        @Test
        @DisplayName("a KO status is not mistaken for a KO row count")
        void doesNotReadTheStatusAsACount() throws IOException {

            AnalysisReport report = analyzeStdout(batchHeader() + layerBlock("CORE", "KO"));

            assertThat(report.overallStatus()).isEqualTo("FAILED");
            assertThat(report.totalKo())
                    .as("STATUS : KO carries no number, so there is nothing to count")
                    .isZero();
            assertThat(report.completedEtlLayers()).singleElement()
                    .extracting(EtlLayerDto::koCount)
                    .isNull();
        }

        @Test
        @DisplayName("totals skip layers that never reported a count")
        void sumsOnlyReportedCounts() throws IOException {
            String log = batchHeader()
                    + layerBlock("STAGING", "OK")
                    + """
                      START CORE
                      IDEXECUTION : 10242
                      Starting time : 01/08/2026 22:18:52
                      End time : 01/08/2026 22:41:37
                      STATUS : OK
                      """;

            AnalysisReport report = analyzeStdout(log);

            assertThat(report.totalRawData()).isEqualTo(100);
            assertThat(report.totalOk()).isEqualTo(100);
        }
    }

    // ---- Timestamps and durations ----------------------------------------------------------

    @Nested
    @DisplayName("Timestamps")
    class Timestamps {

        @Test
        @DisplayName("layer timestamps in dd/MM/yyyy become an ISO range and a H:MM:SS duration")
        void parsesSlashTimestampsInLayerMode() throws IOException {
            AnalysisReport report = analyzeStdout(batchHeader() + layerBlock("CORE", "OK"));

            assertThat(report.overallStartTime()).isEqualTo("2026-08-01T22:00:04");
            assertThat(report.overallEndTime()).isEqualTo("2026-08-01T22:41:37");
            assertThat(report.overallDuration()).isEqualTo("0:41:33");
        }

        @Test
        @DisplayName("legacy logs timestamped dd/MM/yyyy get a real range, not N/A")
        void parsesSlashTimestampsInLegacyMode() throws IOException {
            String log = """
                    01/08/2026 23:10:02 [INFO ] Launching job LegacyLoader
                    01/08/2026 23:12:45 [INFO ] finished
                    """;

            AnalysisReport report = analyzeStdout(log);

            assertThat(report.mode()).isEqualTo("legacy");
            assertThat(report.overallStartTime()).isEqualTo("2026-08-01T23:10:02");
            assertThat(report.overallEndTime()).isEqualTo("2026-08-01T23:12:45");
            assertThat(report.overallDuration()).isEqualTo("0:02:43");
        }

        @Test
        @DisplayName("legacy logs timestamped yyyy-MM-dd are parsed too")
        void parsesDashTimestampsInLegacyMode() throws IOException {
            String log = """
                    2026-08-01 23:10:02 [INFO ] Launching job LegacyLoader
                    2026-08-01 23:15:12 [INFO ] finished
                    """;

            AnalysisReport report = analyzeStdout(log);

            assertThat(report.overallStartTime()).isEqualTo("2026-08-01T23:10:02");
            assertThat(report.overallDuration()).isEqualTo("0:05:10");
        }

        @Test
        @DisplayName("dates inside SQL statements do not widen the range")
        void ignoresTimestampsInsideSqlMutations() throws IOException {
            String log = """
                    01/08/2026 23:10:02 [INFO ] Launching job LegacyLoader
                    insert into audit (run_at) values ('01/08/2020 01:00:00')
                    01/08/2026 23:12:45 [INFO ] finished
                    """;

            AnalysisReport report = analyzeStdout(log);

            assertThat(report.overallStartTime()).isEqualTo("2026-08-01T23:10:02");
        }

        @Test
        @DisplayName("an end before the start keeps a single leading minus")
        void formatsNegativeDurationReadably() throws IOException {
            String log = batchHeader() + """
                    START CORE
                    IDEXECUTION : 1
                    Starting time : 01/08/2026 22:00:00
                    End time : 01/08/2026 21:58:30
                    STATUS : OK
                    """;

            AnalysisReport report = analyzeStdout(log);

            assertThat(report.completedEtlLayers()).singleElement()
                    .extracting(EtlLayerDto::duration)
                    .isEqualTo("-0:01:30");
        }

        @Test
        @DisplayName("an unparsable timestamp degrades to N/A rather than throwing")
        void fallsBackToNotAvailable() throws IOException {
            String log = batchHeader() + """
                    START CORE
                    IDEXECUTION : 1
                    Starting time : 99/99/9999 99:99:99
                    STATUS : OK
                    """;

            AnalysisReport report = analyzeStdout(log);

            assertThat(report.completedEtlLayers()).singleElement()
                    .extracting(EtlLayerDto::startTime, EtlLayerDto::duration)
                    .containsExactly("N/A", "N/A");
        }
    }

    // ---- Progress -------------------------------------------------------------------------

    @Nested
    @DisplayName("Progress reporting")
    class Progress {

        @Test
        @DisplayName("the last progress line wins and remaining is derived from it")
        void reportsLatestProgressForTheRunningLayer() throws IOException {
            String log = batchHeader() + """
                    START CORE
                    IDEXECUTION : 10242
                    Starting time : 01/08/2026 22:18:52
                    Items processed: 100 / 800 --> 12.5%
                    Items processed: 320 / 800 --> 40.0%
                    """;

            AnalysisReport report = analyzeStdout(log);
            CurrentProgressDto detail = report.currentProgressDetail();

            assertThat(detail).isNotNull();
            assertThat(detail.name()).isEqualTo("CORE");
            assertThat(detail.processed()).isEqualTo(320);
            assertThat(detail.total()).isEqualTo(800);
            assertThat(detail.remaining()).isEqualTo(480);
            assertThat(detail.percent()).isEqualTo("40.0");
            assertThat(report.currentProgress()).isEqualTo("CORE: 320/800 (40.0%)");
        }

        @Test
        @DisplayName("a running legacy log falls back to the last non-blank line")
        void reportsLastActivityWhenNoProgressLine() throws IOException {
            String log = """
                    01/08/2026 23:10:02 [INFO ] Launching job LegacyLoader
                    01/08/2026 23:10:30 [INFO ] still working

                    """;

            AnalysisReport report = analyzeStdout(log);

            assertThat(report.overallStatus()).isEqualTo("RUNNING");
            assertThat(report.currentProgressDetail().lastActivity())
                    .isEqualTo("01/08/2026 23:10:30 [INFO ] still working");
        }
    }

    // ---- Failure details -------------------------------------------------------------------

    @Nested
    @DisplayName("Failure details")
    class Failures {

        @Test
        @DisplayName("details absent from the log are null, not the string N/A")
        void leavesUnknownFailureFieldsNull() throws IOException {
            AnalysisReport report = analyzeStdout(batchHeader() + layerBlock("CORE", "KO"));

            assertThat(report.overallStatus()).isEqualTo("FAILED");
            assertThat(report.failedComponent()).isNull();
            assertThat(report.mainError()).isNull();
            assertThat(report.rootCause()).isNull();
            assertThat(report.stackTrace()).isNull();
            assertThat(report.failedQuery()).isNull();
        }

        @Test
        @DisplayName("nothing is reported as failed while the run is healthy")
        void reportsNoFailureDetailsOnSuccess() throws IOException {
            AnalysisReport report = analyzeStdout(batchHeader() + layerBlock("CORE", "OK"));

            assertThat(report.overallStatus()).isEqualTo("SUCCESS");
            assertThat(report.jobId()).isNull();
            assertThat(report.dagName()).isNull();
            assertThat(report.failedComponent()).isNull();
        }

        @Test
        @DisplayName("component, error, cause, trace and query are pulled from stderr")
        void extractsFailureDetailsFromStderr() throws IOException {
            String stderr = """
                    INFO: Query exec
                    INSERT INTO core.client (client_id) VALUES (?)

                    Exception in component tPostgresqlOutput_3
                    java.sql.SQLException: null value in column "client_id"
                    Caused by: org.postgresql.util.PSQLException: null value
                    """;

            AnalysisReport report = service.analyze(
                    file("stdout", "stdout_nightly_load.txt", batchHeader() + layerBlock("CORE", "KO")),
                    file("stderr", "stderr_nightly_load.txt", stderr));

            assertThat(report.failedComponent()).isEqualTo("tPostgresqlOutput_3");
            assertThat(report.mainError()).isEqualTo("java.sql.SQLException: null value in column \"client_id\"");
            assertThat(report.rootCause()).startsWith("Caused by:");
            assertThat(report.stackTrace()).contains("Exception in component tPostgresqlOutput_3");
            assertThat(report.failedQuery())
                    .contains("Query exec")
                    .contains("INSERT INTO core.client");
        }

        @Test
        @DisplayName("each extracted detail reports the file and line it was taken from")
        void reportsWhereEachDetailCameFrom() throws IOException {

            String stderr = """
                    INFO: Query exec
                    INSERT INTO core.client (client_id) VALUES (?)

                    Exception in component tPostgresqlOutput_3
                    java.sql.SQLException: null value in column "client_id"
                    Caused by: org.postgresql.util.PSQLException: null value
                    """;

            AnalysisReport report = service.analyze(
                    file("stdout", "stdout_nightly.txt", batchHeader() + layerBlock("CORE", "KO")),
                    file("stderr", "stderr_nightly.txt", stderr));

            assertThat(report.sourceLocations()).isNotNull();
            assertThat(report.sourceLocations().failedQuery())
                    .as("the query block starts on stderr line 1")
                    .isEqualTo("stderr:1");
            assertThat(report.sourceLocations().failedComponent()).isEqualTo("stderr:4");
            assertThat(report.sourceLocations().mainError()).isEqualTo("stderr:5");
            assertThat(report.sourceLocations().rootCause()).isEqualTo("stderr:6");
            assertThat(report.sourceLocations().stackTrace()).isEqualTo("stderr:4");
        }

        @Test
        @DisplayName("details found only in stdout are reported as coming from stdout")
        void reportsStdoutWhenThatIsWhereTheDetailIs() throws IOException {

            String stdout = batchHeader() + layerBlock("CORE", "KO")
                    + "Exception in component tMap_9\n";

            AnalysisReport report = service.analyze(file("stdout", "stdout_nightly.txt", stdout), null);

            assertThat(report.failedComponent()).isEqualTo("tMap_9");
            assertThat(report.sourceLocations().failedComponent()).startsWith("stdout:");
        }

        @Test
        @DisplayName("nothing found means no location, rather than a misleading one")
        void reportsNoLocationsWhenNothingWasFound() throws IOException {

            AnalysisReport report = analyzeStdout(batchHeader() + layerBlock("CORE", "KO"));

            assertThat(report.sourceLocations().mainError()).isNull();
            assertThat(report.sourceLocations().stackTrace()).isNull();
        }

        @Test
        @DisplayName("job id comes from the uploaded file name, and only for failures")
        void derivesJobIdFromFileName() throws IOException {
            AnalysisReport report = service.analyze(
                    file("stdout", "stdout_nightly_load_20260801.txt", batchHeader() + layerBlock("CORE", "KO")),
                    null);

            assertThat(report.jobId()).isEqualTo("nightly_load_20260801");
        }

        @ParameterizedTest(name = "{0} -> job id {1}")
        @CsvSource({
                "stdout_job42.txt,   job42",
                "stderr-job42.log,   job42",
                "job42_stdout.txt,   job42",
                "job42.log,          job42"
        })
        void readsJobIdFromVariousFileNames(String fileName, String expected) throws IOException {
            AnalysisReport report = service.analyze(
                    file("stdout", fileName, batchHeader() + layerBlock("CORE", "KO")), null);

            assertThat(report.jobId()).isEqualTo(expected);
        }
    }

    // ---- Job name -------------------------------------------------------------------------

    @Nested
    @DisplayName("Job name detection")
    class JobName {

        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "'**** BATCH : NIGHTLY_LOAD ****',        NIGHTLY_LOAD",
                "'BATCH : NIGHTLY_LOAD',                  NIGHTLY_LOAD",
                "'Launching job LegacyLoader',         LegacyLoader",
                "'Job : NightlyLoad',                  NightlyLoad",
                "'JobName = NightlyLoad',              NightlyLoad",
                "'TalendJobName = NightlyLoad',        NightlyLoad",
                "'core_Postgres_42 started',           core_Postgres_42"
        })
        @DisplayName("every supported pattern still matches after being literal-guarded")
        void findsJobNameForEachPattern(String line, String expected) throws IOException {
            AnalysisReport report = analyzeStdout(line + "\n");

            assertThat(report.jobName()).isEqualTo(expected);
        }

        @Test
        @DisplayName("an unrecognisable log reports Unknown rather than failing")
        void fallsBackToUnknown() throws IOException {
            AnalysisReport report = analyzeStdout("nothing identifiable here\n");

            assertThat(report.jobName()).isEqualTo("Unknown");
        }
    }

    // ---- Both uploads together ---------------------------------------------------------------

    @Nested
    @DisplayName("stdout and stderr are both reported, not one at the other's expense")
    class BothStreams {

        /** A run that processed data and finished OK, while stderr recorded problems anyway. */
        private static final String ERROR_STDERR = """
                02/08/2026 22:30:00 INFO: Query exec
                insert into core.client (client_id, name) values (?, ?)
                02/08/2026 22:30:05 ERROR: null value in column "client_id" violates not-null constraint
                02/08/2026 22:30:06 ERROR Exception in component tPostgresqlOutput_3
                java.sql.SQLException: null value in column "client_id"
                Caused by: org.postgresql.util.PSQLException: null value
                02/08/2026 22:31:00 [FATAL] EXIT CODE 1
                """;

        @Test
        @DisplayName("an error-heavy stderr does not stop stdout's processing figures being read")
        void keepsStdoutUnitsWhenStderrLooksLikeAnotherDialect() throws IOException {

            // stderr alone scores higher as a Talend log than this in-progress stdout does as a
            // batch log, and the Talend profile has no units - which used to discard everything.
            String stdout = """
                    START STAGING
                    Starting time : 02/08/2026 22:00:04
                    Raw data : 1000
                    - Error : 0
                    - OK : 1000
                    Items processed: 800 / 1000 --> 80.0%
                    """;

            AnalysisReport report = service.analyze(
                    file("stdout", "stdout_job.txt", stdout),
                    file("stderr", "stderr_job.txt", ERROR_STDERR));

            assertThat(report.detectedFormat()).isEqualTo("batch-layer");
            assertThat(report.inProgressEtlLayers()).extracting(EtlLayerDto::layerName)
                    .containsExactly("STAGING");
            assertThat(report.totalRawData())
                    .as("the row count stdout reported must survive")
                    .isEqualTo(1000);
            assertThat(report.currentProgress()).isEqualTo("STAGING: 800/1000 (80.0%)");
        }

        @Test
        @DisplayName("errors in stderr are reported even when every layer finished OK")
        void reportsStderrErrorsOnARunThatFinishedOk() throws IOException {

            AnalysisReport report = service.analyze(
                    file("stdout", "stdout_job.txt", batchHeader() + layerBlock("CORE", "OK")),
                    file("stderr", "stderr_job.txt", ERROR_STDERR));

            assertThat(report.overallStatus())
                    .as("the layers said OK, so the run is a success")
                    .isEqualTo("SUCCESS");

            assertThat(report.totalRawData()).isEqualTo(100);
            assertThat(report.failedComponent()).isEqualTo("tPostgresqlOutput_3");
            assertThat(report.mainError()).contains("SQLException");
            assertThat(report.rootCause()).startsWith("Caused by:");
            assertThat(report.stackTrace()).isNotNull();
            assertThat(report.failedQuery()).contains("insert into core.client");
            assertThat(report.sourceLocations().mainError()).startsWith("stderr:");
            assertThat(report.issues()).isNotEmpty();
        }

        @Test
        @DisplayName("a clean successful run still reports no error detail at all")
        void staysQuietWhenNothingWentWrong() throws IOException {

            AnalysisReport report = service.analyze(
                    file("stdout", "stdout_job.txt", batchHeader() + layerBlock("CORE", "OK")),
                    file("stderr", "stderr_job.txt", "02/08/2026 22:00:00 INFO all good\n"));

            assertThat(report.overallStatus()).isEqualTo("SUCCESS");
            assertThat(report.failedComponent()).isNull();
            assertThat(report.mainError()).isNull();
            assertThat(report.stackTrace()).isNull();
            assertThat(report.jobId()).as("job id identifies a run to investigate").isNull();
            assertThat(report.issues()).isEmpty();
        }
    }

    // ---- Mode selection and edge cases ------------------------------------------------------

    @Nested
    @DisplayName("Mode selection")
    class Modes {

        @Test
        @DisplayName("START markers select the layer parser")
        void usesLayerModeWhenBlocksExist() throws IOException {
            AnalysisReport report = analyzeStdout(batchHeader() + layerBlock("CORE", "OK"));

            assertThat(report.mode()).isEqualTo("layers");
        }

        @Test
        @DisplayName("without START markers the legacy analysis runs and counts keywords")
        void usesLegacyModeOtherwise() throws IOException {
            String log = """
                    2026-08-01 23:10:02 [ERROR] first problem
                    2026-08-01 23:10:03 [WARNING] be careful
                    2026-08-01 23:10:04 [ERROR] second problem
                    2026-08-01 23:10:05 [INFO ] JOB COMPLETED
                    """;

            AnalysisReport report = analyzeStdout(log);

            assertThat(report.mode()).isEqualTo("legacy");
            // The run completed, but it logged warnings - reporting a plain SUCCESS would read as
            // a clean job, so the status is upgraded rather than left as SUCCESS.
            assertThat(report.overallStatus()).isEqualTo("FINISHED_OK_WARNINGS");
            assertThat(report.totalError()).isEqualTo(2);
            assertThat(report.totalWarning()).isEqualTo(1);
            assertThat(report.completedEtlLayers()).isEmpty();
        }

        @Test
        @DisplayName("a completed run with no warnings stays a plain SUCCESS")
        void keepsPlainSuccessWithoutWarnings() throws IOException {
            String log = """
                    2026-08-01 23:10:02 [INFO ] loading
                    2026-08-01 23:10:05 [INFO ] JOB COMPLETED
                    """;

            AnalysisReport report = analyzeStdout(log);

            assertThat(report.overallStatus()).isEqualTo("SUCCESS");
            assertThat(report.totalWarning()).isZero();
        }

        @Test
        @DisplayName("'START : <time>' headers are not mistaken for a layer block")
        void ignoresTheStartTimeHeader() throws IOException {
            AnalysisReport report = analyzeStdout(batchHeader());

            assertThat(report.mode()).isEqualTo("legacy");
        }

        @Test
        @DisplayName("empty uploads produce a report instead of an exception")
        void handlesEmptyInput() throws IOException {
            AnalysisReport report = service.analyze(file("stdout", "stdout_empty.txt", ""), null);

            assertThat(report.mode()).isEqualTo("legacy");
            assertThat(report.overallStartTime()).isEqualTo("N/A");
            assertThat(report.jobName()).isEqualTo("Unknown");
            assertThat(report.talendComponents()).isEmpty();
        }

        @Test
        @DisplayName("Talend components are collected, de-duplicated and sorted")
        void collectsTalendComponents() throws IOException {
            String log = """
                    tMap_2 running
                    tFileInputDelimited_1 reading
                    tMap_2 running again
                    """;

            AnalysisReport report = analyzeStdout(log);

            assertThat(report.talendComponents())
                    .containsExactly("tFileInputDelimited_1", "tMap_2");
        }
    }

    // ---- Helpers ---------------------------------------------------------------------------

    private AnalysisReport analyzeStdout(String content) throws IOException {
        return service.analyze(file("stdout", "stdout_job42.txt", content), null);
    }

    private MultipartFile file(String part, String fileName, String content) {
        return new MockMultipartFile(part, fileName, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }

    private static String batchHeader() {
        return """
                **** BATCH : NIGHTLY_LOAD ****
                START : 01/08/2026 22:00:04
                """;
    }

    private static String layerBlock(String name, String status) {
        return """
                START %s
                IDEXECUTION : 10241
                Starting time : 01/08/2026 22:00:04
                Raw data : 100
                - Error : 0
                - Warning : 0
                - OK : 100
                End time : 01/08/2026 22:41:37
                STATUS : %s
                """.formatted(name, status);
    }
}
