// src/main/java/com/fourj/searchservice/exception/GlobalExceptionHandler.java
package com.fourj.searchservice.exception;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(SearchServiceException.class)
    public ResponseEntity<ErrorResponse> handleSearchServiceException(SearchServiceException ex) {
        log.error("SearchServiceException: {} - {}", ex.getErrorCode(), ex.getMessage(), ex);
        
        ErrorResponse response = new ErrorResponse(
                ex.getErrorCode(),
                ex.getMessage(),
                System.currentTimeMillis()
        );
        
        return ResponseEntity.status(ex.getStatus()).body(response);
    }
    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error("Unhandled exception: ", ex);
        
        ErrorResponse response = new ErrorResponse(
                "INTERNAL_ERROR",
                "An unexpected error occurred",
                System.currentTimeMillis()
        );
        
        return ResponseEntity.status(500).body(response);
    }
    
    @Data
    @AllArgsConstructor
    public static class ErrorResponse {
        private String code;
        private String message;
        private long timestamp;
    }
}