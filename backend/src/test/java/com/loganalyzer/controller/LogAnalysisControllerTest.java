package com.loganalyzer.controller;

import com.loganalyzer.model.AnalysisReport;
import com.loganalyzer.model.KoReportDto;
import com.loganalyzer.model.LayerErrorDetailDto;
import com.loganalyzer.model.RecordStatusDto;
import com.loganalyzer.service.KoReportService;
import com.loganalyzer.service.LayerErrorDetailService;
import com.loganalyzer.service.profile.ProfileRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LogAnalysisController.class)
// The real registry has no dependencies of its own, so importing it exercises the actual
// registered profiles rather than a stand-in list that could drift from what /formats returns.
@Import(ProfileRegistry.class)
class LogAnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private KoReportService koReportService;

    @MockBean
    private LayerErrorDetailService layerErrorDetailService;

    @Test
    @DisplayName("an uploaded log is analysed and returned as JSON")
    void returnsReportForAnUpload() throws Exception {
        given(koReportService.generate(any(), any())).willReturn(koReport("SUCCESS", List.of()));

        mockMvc.perform(multipart("/api/analyze").file(logFile("stdout")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis.mode").value("layers"))
                .andExpect(jsonPath("$.analysis.overallStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.hasRecordLevelData").value(false));
    }

    @Test
    @DisplayName("per-record outcomes ride along with the analysis in one response")
    void returnsRecordLevelDataWhenTheFormatSupportsIt() throws Exception {
        RecordStatusDto record = new RecordStatusDto("415893605", "-", "452632041", "KO", "config missing");
        given(koReportService.generate(any(), any())).willReturn(koReport("SUCCESS", List.of(record)));

        mockMvc.perform(multipart("/api/analyze").file(logFile("stdout")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasRecordLevelData").value(true))
                .andExpect(jsonPath("$.records[0].dialogueId").value("415893605"))
                .andExpect(jsonPath("$.records[0].status").value("KO"))
                .andExpect(jsonPath("$.records[0].koReason").value("config missing"));
    }

    @Test
    @DisplayName("either part may be omitted, but not both")
    void rejectsARequestWithNoFiles() throws Exception {
        mockMvc.perform(multipart("/api/analyze"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("At least one of stdout or stderr file is required."));

        verifyNoInteractions(koReportService);
    }

    @Test
    @DisplayName("an empty file counts as no file")
    void rejectsAnEmptyUpload() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("stdout", "stdout_job.txt", "text/plain", new byte[0]);

        mockMvc.perform(multipart("/api/analyze").file(empty))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        verifyNoInteractions(koReportService);
    }

    @Test
    @DisplayName("an unreadable upload answers with the shared error shape, not a stack trace")
    void reportsReadFailuresAsJson() throws Exception {
        given(koReportService.generate(any(), any())).willThrow(new IOException("disk gone"));

        mockMvc.perform(multipart("/api/analyze").file(logFile("stdout")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("a per-layer detail file is parsed and returned as JSON")
    void returnsLayerDetailForAnUpload() throws Exception {
        given(layerErrorDetailService.parse(any())).willReturn(new LayerErrorDetailDto(
                "1477181509", "ODS_Policy", 54, 54, 0, 0, "FINISHED_OK_WARNINGS", List.of()));

        mockMvc.perform(multipart("/api/layer-detail").file(logFile("file")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value("1477181509"))
                .andExpect(jsonPath("$.layerName").value("ODS_Policy"));
    }

    @Test
    @DisplayName("a layer-detail request with no file is rejected")
    void rejectsLayerDetailRequestWithNoFile() throws Exception {
        mockMvc.perform(multipart("/api/layer-detail"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A file is required."));

        verifyNoInteractions(layerErrorDetailService);
    }

    @Test
    @DisplayName("health reports UP")
    void exposesHealth() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("formats lists every registered profile, not a hand-kept copy")
    void exposesRegisteredFormats() throws Exception {
        mockMvc.perform(get("/api/formats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'elab-batch')].displayName")
                        .value("Elaboration Batch log"))
                .andExpect(jsonPath("$[?(@.id == 'batch-layer')]").exists())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.not(org.hamcrest.Matchers.empty())));
    }

    private MockMultipartFile logFile(String part) {
        return new MockMultipartFile(part, "stdout_job.txt", "text/plain", "STATUS : OK".getBytes());
    }

    private KoReportDto koReport(String status, List<RecordStatusDto> records) {
        return new KoReportDto(report(status), records, !records.isEmpty());
    }

    private AnalysisReport report(String status) {
        return new AnalysisReport(
                "layers", "batch-layer", 1.0,
                "NIGHTLY_LOAD", null, null, status,
                "2026-08-01T22:00:04", "2026-08-01T22:41:37", "0:41:33",
                100, 0, 0, 100, 0,
                List.of(), List.of(), null, null,
                null, null, null, null, null, null, null, List.of(), List.of(),
                com.loganalyzer.model.SourceLocationsDto.NONE);
    }
}
