// src/main/java/com/fourj/searchservice/exception/KafkaProcessingException.java
package com.fourj.searchservice.exception;

public class KafkaProcessingException extends SearchServiceException {
    private static final String DEFAULT_ERROR_CODE = "KAFKA_ERROR";
    private static final int DEFAULT_STATUS = 500;
    
    public KafkaProcessingException(String message) {
        super(message, DEFAULT_ERROR_CODE, DEFAULT_STATUS);
    }
    
    public KafkaProcessingException(String message, Throwable cause) {
        super(message, DEFAULT_ERROR_CODE, DEFAULT_STATUS, cause);
    }
}