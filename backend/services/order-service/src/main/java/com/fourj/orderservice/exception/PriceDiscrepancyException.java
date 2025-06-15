package com.fourj.orderservice.exception;

public class PriceDiscrepancyException extends RuntimeException {

    public PriceDiscrepancyException(String message) {
        super(message);
    }

    public PriceDiscrepancyException(String message, Throwable cause) {
        super(message, cause);
    }
} 