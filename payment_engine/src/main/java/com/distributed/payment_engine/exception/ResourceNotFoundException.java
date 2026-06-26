package com.distributed.payment_engine.exception;

/**
 * Thrown when a requested resource (Payment, Wallet, etc.) does not exist.
 * Handled globally by GlobalExceptionHandler → returns 404 NOT_FOUND.
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
