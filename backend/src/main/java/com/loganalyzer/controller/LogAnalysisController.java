package com.loganalyzer.controller;

import com.loganalyzer.model.FormatInfoDto;
import com.loganalyzer.service.KoReportService;
import com.loganalyzer.service.profile.ProfileRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class LogAnalysisController {

    private static final Logger log = LoggerFactory.getLogger(LogAnalysisController.class);

    private final KoReportService koReportService;
    private final ProfileRegistry profileRegistry;

    public LogAnalysisController(KoReportService koReportService, ProfileRegistry profileRegistry) {
        this.koReportService = koReportService;
        this.profileRegistry = profileRegistry;
    }

    /**
     * Analyses uploaded log files.
     *
     * <p>Returns a {@link com.loganalyzer.model.KoReportDto} containing the full
     * {@link com.loganalyzer.model.AnalysisReport} plus per-record outcomes when the detected
     * log format supports them. The frontend receives everything it needs in one call so the
     * user only has to upload files once.
     */
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> analyze(
            @RequestParam(value = "stdout", required = false) MultipartFile stdoutFile,
            @RequestParam(value = "stderr", required = false) MultipartFile stderrFile
    ) {
        boolean hasStdout = stdoutFile != null && !stdoutFile.isEmpty();
        boolean hasStderr = stderrFile != null && !stderrFile.isEmpty();

        if (!hasStdout && !hasStderr) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "At least one of stdout or stderr file is required."));
        }

        try {
            return ResponseEntity.ok(koReportService.generate(stdoutFile, stderrFile));
        } catch (IOException e) {
            log.error("Failed to read uploaded log file(s)", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to read uploaded file(s). Please verify the files and try again."));
        }
    }

    /**
     * The log dialects this analyzer can recognise.
     *
     * <p>Read from {@link ProfileRegistry} rather than a list kept in the frontend, so what the UI
     * tells the user is supported can never drift from what the backend actually detects.
     */
    @GetMapping("/formats")
    public List<FormatInfoDto> formats() {
        return profileRegistry.profiles().stream()
                .map(profile -> new FormatInfoDto(profile.id(), profile.displayName()))
                .toList();
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
