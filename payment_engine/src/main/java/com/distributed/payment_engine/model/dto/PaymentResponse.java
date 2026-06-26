package com.distributed.payment_engine.model.dto;

import com.distributed.payment_engine.model.entity.Payment;

import java.time.Instant;

public class PaymentResponse {
    private Long paymentId;
    private Long fromWalletId;
    private Long toWalletId;
    private Long amount;
    private String status;
    private String paymentType;
    private String idempotencyKey;
    private String merchantOrderId;
    private int retryCount;
    private String createdAt;

    // Static factory method — converts entity to response
    public static PaymentResponse from(Payment payment) {
        PaymentResponse res = new PaymentResponse();
        res.paymentId = payment.getPaymentId();
        res.fromWalletId = payment.getFromWalletId();
        res.toWalletId = payment.getToWalletId();
        res.amount = payment.getAmount();
        res.status = payment.getStatus().name();
        res.paymentType = payment.getPaymentType().name();
        res.idempotencyKey = payment.getIdempotencyKey();
        res.merchantOrderId = payment.getMerchantOrderId();
        res.retryCount = payment.getRetryCount();
        res.createdAt = Instant.ofEpochMilli(payment.getCreatedAt()).toString();
        return res;
    }

    public Long getPaymentId() { return paymentId; }
    public Long getFromWalletId() { return fromWalletId; }
    public Long getToWalletId() { return toWalletId; }
    public Long getAmount() { return amount; }
    public String getStatus() { return status; }
    public String getPaymentType() { return paymentType; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getMerchantOrderId() { return merchantOrderId; }
    public int getRetryCount() { return retryCount; }
    public String getCreatedAt() { return createdAt; }
}
