package com.loganalyzer.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler("50MB");

    @Test
    @DisplayName("an oversized upload answers 413 with the configured limit, in the shared error shape")
    void reportsTheLimitWhenAnUploadIsTooLarge() {
        ResponseEntity<Map<String, String>> response =
                handler.handleUploadTooLarge(new MaxUploadSizeExceededException(52_428_800L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody())
                .containsOnlyKeys("error")
                .extractingByKey("error")
                .asString()
                .contains("50MB");
    }

    @Test
    @DisplayName("a malformed multipart request answers 400 in the same shape")
    void reportsMalformedUploads() {
        ResponseEntity<Map<String, String>> response =
                handler.handleMalformedUpload(new MultipartException("boundary missing"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsOnlyKeys("error");
    }
}
