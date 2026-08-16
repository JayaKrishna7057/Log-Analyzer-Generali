package com.loganalyzer.service;

import com.loganalyzer.model.KoReportDto;
import com.loganalyzer.model.RecordStatusDto;
import com.loganalyzer.service.profile.ProfileMatch;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Builds the KO report: the standard analysis plus per-record outcomes.
 *
 * <p>Both halves come from a single read and a single profile decision. Reading the upload again
 * would double the I/O on multi-MB logs, and resolving the profile again by confidence alone could
 * pick a different dialect than the one that produced the report - leaving a report that names one
 * format while its records were parsed by another.
 *
 * <p>Formats with no notion of individual records return an empty list, which the DTO reports as
 * {@code hasRecordLevelData=false} so the UI can say so rather than showing an empty table.
 */
@Service
public class KoReportService {

    private final LogAnalyzerService logAnalyzerService;

    public KoReportService(LogAnalyzerService logAnalyzerService) {
        this.logAnalyzerService = logAnalyzerService;
    }

    public KoReportDto generate(MultipartFile stdoutFile, MultipartFile stderrFile) throws IOException {

        LogAnalyzerService.Analysis analysis = logAnalyzerService.analyzeUploads(stdoutFile, stderrFile);

        ProfileMatch match = analysis.selection().match();
        List<RecordStatusDto> records = match.matched()
                ? match.profile().parseRecords(analysis.source())
                : List.of();

        return new KoReportDto(analysis.report(), records, !records.isEmpty());
    }
}
