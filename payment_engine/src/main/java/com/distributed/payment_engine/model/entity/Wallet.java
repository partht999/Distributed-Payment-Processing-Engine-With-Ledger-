package com.distributed.payment_engine.model.entity;

import com.distributed.payment_engine.model.enums.WalletStatus;
import jakarta.persistence.*;

@Entity
@Table(name = "wallets")
public class Wallet {
    @Id
    private Long walletId;
    private Long balance;
    private Long userId;
    private Long phoneNumber;
    
    @Enumerated(EnumType.STRING)
    private WalletStatus status;

    public Wallet() {} // Required by JPA

    public Wallet(Long walletId, Long balance, Long userId, Long phoneNumber, WalletStatus status) {
        this.walletId = walletId;
        this.balance = balance;
        this.userId = userId;
        this.phoneNumber = phoneNumber;
        this.status = status;
    }

    public Long getWalletId() { return walletId; }
    public Long getBalance() { return balance; }
    public void setBalance(Long balance) { this.balance = balance; }
    public Long getUserId() { return userId; }
    public Long getPhoneNumber() { return phoneNumber; }
    public WalletStatus getStatus() { return status; }
    public void setStatus(WalletStatus status) { this.status = status; }

    @Override
    public String toString() {
        return "Wallet{id=" + walletId + ", balance=" + balance + ", status=" + status + "}";
    }
}
