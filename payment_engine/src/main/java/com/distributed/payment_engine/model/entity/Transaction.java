package com.distributed.payment_engine.model.entity;

import com.distributed.payment_engine.model.enums.TransactionType;
import jakarta.persistence.*;

@Entity
@Table(name = "transactions")
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long transactionId;
    private Long fromWalletId;
    private Long toWalletId;
    private Long amount;
    
    @Enumerated(EnumType.STRING)
    private TransactionType type;
    
    private Long paymentId;

    public Transaction() {} // Required by JPA

    public Transaction(Long transactionId, Long fromWalletId, Long toWalletId,
                       Long amount, TransactionType type, Long paymentId) {
        this.transactionId = transactionId;
        this.fromWalletId = fromWalletId;
        this.toWalletId = toWalletId;
        this.amount = amount;
        this.type = type;
        this.paymentId = paymentId;
    }

    public Long getTransactionId() { return transactionId; }
    public Long getFromWalletId() { return fromWalletId; }
    public Long getToWalletId() { return toWalletId; }
    public Long getAmount() { return amount; }
    public TransactionType getType() { return type; }
    public Long getPaymentId() { return paymentId; }

    @Override
    public String toString() {
        return "Transaction{id=" + transactionId + ", from=" + fromWalletId +
                ", to=" + toWalletId + ", amount=" + amount + ", type=" + type + "}";
    }
}
