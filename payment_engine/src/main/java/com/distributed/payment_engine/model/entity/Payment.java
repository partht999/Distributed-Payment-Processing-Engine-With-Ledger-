package com.distributed.payment_engine.model.entity;

import com.distributed.payment_engine.model.enums.PaymentStatus;
import com.distributed.payment_engine.model.enums.PaymentType;
import jakarta.persistence.*;

@Entity
@Table(name = "payments", indexes = {
    @Index(name = "idx_payments_merchant_order_id", columnList = "merchantOrderId")
})
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long paymentId;
    private Long fromWalletId;
    private Long toWalletId;
    private Long amount;
    
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    @Column(unique = true)
    private String idempotencyKey;
    private int retryCount;
    private int maxRetries;
    private String merchantOrderId;
    private long createdAt;
    
    @Enumerated(EnumType.STRING)
    private PaymentType paymentType;

    public Payment() {} // Required by JPA

    public Payment(Long paymentId, Long fromWalletId, Long toWalletId, Long amount,
                   PaymentStatus status, String idempotencyKey, String merchantOrderId, PaymentType paymentType) {
        this.paymentId = paymentId;
        this.fromWalletId = fromWalletId;
        this.toWalletId = toWalletId;
        this.amount = amount;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.merchantOrderId = merchantOrderId;
        this.paymentType = paymentType;
        this.maxRetries = 3;
        this.retryCount = 0;
        this.createdAt = System.currentTimeMillis();
    }

    public Long getPaymentId() { return paymentId; }
    public Long getFromWalletId() { return fromWalletId; }
    public Long getToWalletId() { return toWalletId; }
    public Long getAmount() { return amount; }
    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus status) { this.status = status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getMerchantOrderId() { return merchantOrderId; }
    public long getCreatedAt() { return createdAt; }
    public PaymentType getPaymentType() { return paymentType; }
    public int getRetryCount() { return retryCount; }
    public int getMaxRetries() { return maxRetries; }
    public void incrementRetryCount() { retryCount++; }

    @Override
    public String toString() {
        return "Payment{id=" + paymentId + ", from=" + fromWalletId + ", to=" + toWalletId +
                ", amount=" + amount + ", status=" + status + ", type=" + paymentType + "}";
    }
}
