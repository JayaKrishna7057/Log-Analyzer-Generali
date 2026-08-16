package com.loganalyzer.service;

import com.loganalyzer.model.KoReportDto;
import com.loganalyzer.model.RecordStatusDto;
import com.loganalyzer.service.profile.ProfileRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The KO report is derived from the same parse as the analysis, so these tests drive the real
 * profile registry rather than mocks - a stubbed profile would not catch the case this service
 * exists to prevent, where the report names one dialect and the records come from another.
 */
class KoReportServiceTest {

    private final KoReportService service =
            new KoReportService(new LogAnalyzerService(new ProfileRegistry()));

    @Test
    @DisplayName("per-record outcomes are extracted from a format that tracks them")
    void extractsRecordsFromAnElaborationBatchLog() throws IOException {
        KoReportDto result = service.generate(stdout(elabBatchLog()), null);

        assertThat(result.hasRecordLevelData()).isTrue();
        assertThat(result.records()).hasSize(2);

        RecordStatusDto ok = result.records().get(0);
        assertThat(ok.dialogueId()).isEqualTo("111");
        assertThat(ok.status()).isEqualTo("OK");
        assertThat(ok.koReason()).isNull();

        RecordStatusDto ko = result.records().get(1);
        assertThat(ko.dialogueId()).isEqualTo("222");
        assertThat(ko.status()).isEqualTo("KO");
        assertThat(ko.koReason()).isEqualTo("Configurazione non presente");
    }

    @Test
    @DisplayName("the records come from the same profile the report names")
    void recordsAgreeWithTheReportedFormat() throws IOException {
        KoReportDto result = service.generate(stdout(elabBatchLog()), null);

        assertThat(result.analysis().detectedFormat()).isEqualTo("elab-batch");
        assertThat(result.records()).isNotEmpty();
    }

    @Test
    @DisplayName("a format with no per-record data reports that rather than an empty table")
    void reportsAbsenceOfRecordDataForOtherFormats() throws IOException {
        String log = """
                2026-08-01 23:10:02 [ERROR] something broke
                2026-08-01 23:10:05 [INFO ] JOB COMPLETED
                """;

        KoReportDto result = service.generate(stdout(log), null);

        assertThat(result.hasRecordLevelData()).isFalse();
        assertThat(result.records()).isEmpty();
        assertThat(result.analysis()).isNotNull();
    }

    @Test
    @DisplayName("an empty upload still produces a report instead of throwing")
    void handlesEmptyInput() throws IOException {
        KoReportDto result = service.generate(stdout(""), null);

        assertThat(result.analysis()).isNotNull();
        assertThat(result.records()).isEmpty();
        assertThat(result.hasRecordLevelData()).isFalse();
    }

    /** A minimal log carrying the markers ElabBatchProfile detects, plus two records. */
    private String elabBatchLog() {
        return """
                BATCH_VERSION: 1.2.3
                RunId: RUN-42
                @t@[01/08/2026 15:10:46]
                IDDIALOGUE: 111 - INTERNALKEY: K1 - UNIQUECODE: U1
                STATUS: OK
                IDDIALOGUE: 222 - INTERNALKEY: K2 - UNIQUECODE: U2
                STATUS: KO
                RECORD SCARTATO : PSNHISTDIALOGUEMSG.IDDIALOGUEMSG [222] : Configurazione non presente
                SUMMARY START
                Record to elaborate : 2
                Record Succesful  : 1
                Record Discarded  : 1
                SUMMARY END
                ESTADO: FINISHED_OK_WARNINGS
                @T@[01/08/2026 15:12:52]
                """;
    }

    private MultipartFile stdout(String content) {
        return new MockMultipartFile(
                "stdout", "stdout_job.txt", "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }
}
