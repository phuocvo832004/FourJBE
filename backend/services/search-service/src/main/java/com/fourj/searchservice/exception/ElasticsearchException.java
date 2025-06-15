// src/main/java/com/fourj/searchservice/exception/ElasticsearchException.java
package com.fourj.searchservice.exception;

public class ElasticsearchException extends SearchServiceException {
    private static final String DEFAULT_ERROR_CODE = "ES_ERROR";
    private static final int DEFAULT_STATUS = 500;
    
    public ElasticsearchException(String message) {
        super(message, DEFAULT_ERROR_CODE, DEFAULT_STATUS);
    }
    
    public ElasticsearchException(String message, Throwable cause) {
        super(message, DEFAULT_ERROR_CODE, DEFAULT_STATUS, cause);
    }
    
    public ElasticsearchException(String message, String errorCode) {
        super(message, errorCode, DEFAULT_STATUS);
    }
}