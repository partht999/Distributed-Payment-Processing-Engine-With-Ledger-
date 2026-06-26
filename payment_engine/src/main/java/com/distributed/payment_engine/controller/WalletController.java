package com.distributed.payment_engine.controller;

import com.distributed.payment_engine.exception.ResourceNotFoundException;
import com.distributed.payment_engine.model.dto.AmountRequest;
import com.distributed.payment_engine.model.dto.CreateWalletRequest;
import com.distributed.payment_engine.model.dto.WalletResponse;
import com.distributed.payment_engine.model.entity.Wallet;
import com.distributed.payment_engine.service.WalletService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/wallets")
public class WalletController {

    @Autowired
    private WalletService walletService;

    // POST /api/v1/wallets
    @PostMapping
    public ResponseEntity<WalletResponse> createWallet(@Valid @RequestBody CreateWalletRequest request) {
        Wallet wallet = walletService.createWallet(
                request.getWalletId(),
                request.getBalance() != null ? request.getBalance() : 0L,
                request.getUserId(),
                request.getPhoneNumber() != null ? request.getPhoneNumber() : 0L);
        return ResponseEntity.status(HttpStatus.CREATED).body(WalletResponse.from(wallet));
    }

    // GET /api/v1/wallets/{id}
    @GetMapping("/{id}")
    public ResponseEntity<WalletResponse> getWallet(@PathVariable Long id) {
        Wallet wallet = walletService.getWallet(id);
        if (wallet == null) {
            throw new ResourceNotFoundException("Wallet " + id + " not found");
        }
        return ResponseEntity.ok(WalletResponse.from(wallet));
    }

    // GET /api/v1/wallets/{id}/balance
    @GetMapping("/{id}/balance")
    public ResponseEntity<?> getBalance(@PathVariable Long id) {
        Wallet wallet = walletService.getWallet(id);
        if (wallet == null) {
            throw new ResourceNotFoundException("Wallet " + id + " not found");
        }
        Long ledgerBalance = walletService.getLedgerDerivedBalance(id);
        return ResponseEntity.ok(Map.of(
                "walletId", id,
                "walletBalance", wallet.getBalance(),
                "ledgerDerivedBalance", ledgerBalance
        ));
    }

    // POST /api/v1/wallets/{id}/deposit
    @PostMapping("/{id}/deposit")
    public ResponseEntity<?> deposit(@PathVariable Long id, @Valid @RequestBody AmountRequest request) throws Exception {
        walletService.deposit(id, request.getAmount());
        return ResponseEntity.ok(Map.of(
                "message", "Deposit successful",
                "walletId", id,
                "amount", request.getAmount(),
                "newBalance", walletService.getLedgerDerivedBalance(id)
        ));
    }

    // POST /api/v1/wallets/{id}/withdraw
    @PostMapping("/{id}/withdraw")
    public ResponseEntity<?> withdraw(@PathVariable Long id, @Valid @RequestBody AmountRequest request) throws Exception {
        walletService.withdraw(id, request.getAmount());
        return ResponseEntity.ok(Map.of(
                "message", "Withdrawal successful",
                "walletId", id,
                "amount", request.getAmount(),
                "newBalance", walletService.getLedgerDerivedBalance(id)
        ));
    }

    // GET /api/v1/wallets
    @GetMapping
    public ResponseEntity<List<WalletResponse>> listWallets() {
        List<WalletResponse> wallets = walletService.getAllWallets().stream()
                .map(WalletResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(wallets);
    }
}
