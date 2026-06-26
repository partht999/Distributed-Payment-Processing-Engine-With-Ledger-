package com.distributed.payment_engine.model.enums;

public enum PaymentStatus {
    CREATED,
    AUTHORIZED,  // MONEY BLOCKED
    CAPTURED,    // CAPTURED = SUCCESS (MONEY DEBITED)
    FAILED,
    RETRYING,
    EXPIRED,
    REFUNDED,
    REVERSED
}
