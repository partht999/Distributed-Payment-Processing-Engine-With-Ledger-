package com.distributed.payment_engine.model.dto;

import java.time.Instant;

public class ErrorResponse {
    private String error;
    private String message;
    private int status;
    private String timestamp;

    public ErrorResponse(String error, String message, int status) {
        this.error = error;
        this.message = message;
        this.status = status;
        this.timestamp = Instant.now().toString();
    }

    public String getError() { return error; }
    public String getMessage() { return message; }
    public int getStatus() { return status; }
    public String getTimestamp() { return timestamp; }
}
