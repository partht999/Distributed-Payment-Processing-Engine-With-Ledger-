package com.distributed.payment_engine.model.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public class CreateWalletRequest {
    @NotNull(message = "walletId is required")
    private Long walletId;

    @Min(value = 0, message = "balance cannot be negative")
    private Long balance;

    @NotNull(message = "userId is required")
    private Long userId;

    private Long phoneNumber;

    public Long getWalletId() { return walletId; }
    public void setWalletId(Long walletId) { this.walletId = walletId; }

    public Long getBalance() { return balance; }
    public void setBalance(Long balance) { this.balance = balance; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(Long phoneNumber) { this.phoneNumber = phoneNumber; }
}
