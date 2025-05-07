// src/main/java/com/fourj/searchservice/exception/SearchServiceException.java
package com.fourj.searchservice.exception;

import lombok.Getter;

@Getter
public class SearchServiceException extends RuntimeException {
    private final String errorCode;
    private final int status;
    
    public SearchServiceException(String message, String errorCode, int status) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }
    
    public SearchServiceException(String message, String errorCode, int status, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.status = status;
    }
}