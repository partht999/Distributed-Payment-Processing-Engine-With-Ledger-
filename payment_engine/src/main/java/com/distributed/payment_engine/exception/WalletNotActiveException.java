package com.distributed.payment_engine.exception;

public class WalletNotActiveException extends Exception {
    public WalletNotActiveException(String message) {
        super(message);
    }
}
