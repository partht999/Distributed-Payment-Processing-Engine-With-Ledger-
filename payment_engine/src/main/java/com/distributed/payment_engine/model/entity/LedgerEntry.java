package com.distributed.payment_engine.model.entity;

import com.distributed.payment_engine.model.enums.EntryType;
import jakarta.persistence.*;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long entryId;
    private Long walletId;
    private Long amount;
    
    @Enumerated(EnumType.STRING)
    private EntryType entryType;
    
    private Long paymentId;

    public LedgerEntry() {} // Required by JPA

    public LedgerEntry(Long entryId, Long walletId, Long amount, EntryType entryType, Long paymentId) {
        this.entryId = entryId;
        this.walletId = walletId;
        this.amount = amount;
        this.entryType = entryType;
        this.paymentId = paymentId;
    }

    public Long getEntryId() { return entryId; }
    public Long getWalletId() { return walletId; }
    public Long getAmount() { return amount; }
    public EntryType getEntryType() { return entryType; }
    public Long getPaymentId() { return paymentId; }

    @Override
    public String toString() {
        return "LedgerEntry{id=" + entryId + ", walletId=" + walletId +
                ", amount=" + amount + ", type=" + entryType + "}";
    }
}
