package com.distributed.payment_engine.model.dto;

import com.distributed.payment_engine.model.entity.Wallet;

public class WalletResponse {
    private Long walletId;
    private Long balance;
    private Long userId;
    private String status;

    public static WalletResponse from(Wallet wallet) {
        WalletResponse res = new WalletResponse();
        res.walletId = wallet.getWalletId();
        res.balance = wallet.getBalance();
        res.userId = wallet.getUserId();
        res.status = wallet.getStatus().name();
        return res;
    }

    public Long getWalletId() { return walletId; }
    public Long getBalance() { return balance; }
    public Long getUserId() { return userId; }
    public String getStatus() { return status; }
}
