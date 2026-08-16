package com.loganalyzer.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.Map;

/**
 * Keeps every error the API can return in the same {@code {"error": "..."}} JSON shape the
 * frontend reads. Multipart limits are enforced before the controller runs, so without this
 * advice an oversized upload surfaces as an opaque HTML 500 page.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final String maxFileSize;

    public ApiExceptionHandler(@Value("${spring.servlet.multipart.max-file-size}") String maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        log.warn("Rejected upload larger than {}", maxFileSize);
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(error("Log file is too large. The limit is " + maxFileSize + " per file."));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, String>> handleMalformedUpload(MultipartException e) {
        log.warn("Rejected malformed multipart request", e);
        return ResponseEntity.badRequest()
                .body(error("The upload could not be read. Please re-select the files and try again."));
    }

    private Map<String, String> error(String message) {
        return Map.of("error", message);
    }
}
