package com.distributed.payment_engine.service;

import com.distributed.payment_engine.exception.InsufficientAmountException;
import com.distributed.payment_engine.exception.InvalidAmountException;
import com.distributed.payment_engine.exception.WalletNotActiveException;
import com.distributed.payment_engine.model.entity.LedgerEntry;
import com.distributed.payment_engine.model.entity.Transaction;
import com.distributed.payment_engine.model.entity.Wallet;
import com.distributed.payment_engine.model.enums.EntryType;
import com.distributed.payment_engine.model.enums.TransactionType;
import com.distributed.payment_engine.model.enums.WalletStatus;
import com.distributed.payment_engine.repository.LedgerRepository;
import com.distributed.payment_engine.repository.TransactionRepository;
import com.distributed.payment_engine.repository.WalletRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class WalletService {

    @Autowired
    private WalletRepository walletRepo;

    @Autowired
    private TransactionRepository txnRepo;

    @Autowired
    private LedgerRepository ledgerRepo;

    @Transactional
    public Wallet createWallet(Long walletId, Long balance, Long userId, Long phoneNumber) {
        Wallet wallet = new Wallet(walletId, balance, userId, phoneNumber, WalletStatus.ACTIVE);
        walletRepo.save(wallet);

        if (balance > 0) {
            LedgerEntry entry = new LedgerEntry(null, walletId, balance, EntryType.CREDIT, null);
            ledgerRepo.save(entry);
        }
        return wallet;
    }

    public Wallet getWallet(Long walletId) {
        return walletRepo.findById(walletId).orElse(null);
    }

    public Long getLedgerDerivedBalance(Long walletId) {
        long balance = 0;
        for (LedgerEntry entry : ledgerRepo.findByWalletId(walletId)) {
            if (entry.getEntryType() == EntryType.CREDIT) {
                balance += entry.getAmount();
            } else if (entry.getEntryType() == EntryType.DEBIT) {
                balance -= entry.getAmount();
            }
        }
        return balance;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean transfer(Long fromId, Long toId, Long amount, Long paymentId)
            throws InvalidAmountException, WalletNotActiveException, InsufficientAmountException {

        Wallet from = walletRepo.findById(fromId).orElse(null);
        Wallet to = walletRepo.findById(toId).orElse(null);

        if (from == null) throw new IllegalArgumentException("Wallet " + fromId + " not found");
        if (to == null) throw new IllegalArgumentException("Wallet " + toId + " not found");
        if (from.getStatus() != WalletStatus.ACTIVE) throw new WalletNotActiveException("Wallet " + fromId + " is not active");
        if (to.getStatus() != WalletStatus.ACTIVE) throw new WalletNotActiveException("Wallet " + toId + " is not active");
        if (amount <= 0) throw new InvalidAmountException("Amount must be greater than 0");

        Long ledgerBalance = getLedgerDerivedBalance(fromId);
        if (ledgerBalance < amount) throw new InsufficientAmountException("Insufficient balance");

        from.setBalance(from.getBalance() - amount);
        to.setBalance(to.getBalance() + amount);

        walletRepo.save(from);
        walletRepo.save(to);

        Transaction txn = new Transaction(null, fromId, toId, amount, TransactionType.TRANSFER, paymentId);
        txnRepo.save(txn);

        LedgerEntry debit = new LedgerEntry(null, fromId, amount, EntryType.DEBIT, paymentId);
        LedgerEntry credit = new LedgerEntry(null, toId, amount, EntryType.CREDIT, paymentId);
        ledgerRepo.save(debit);
        ledgerRepo.save(credit);

        return true;
    }

    @Transactional
    public void deposit(Long walletId, Long amount) throws InvalidAmountException, WalletNotActiveException {
        Wallet wallet = walletRepo.findById(walletId).orElse(null);
        if (wallet == null) throw new IllegalArgumentException("Wallet " + walletId + " not found");
        if (wallet.getStatus() != WalletStatus.ACTIVE) throw new WalletNotActiveException("Wallet not active");
        if (amount <= 0) throw new InvalidAmountException("Amount must be greater than 0");

        wallet.setBalance(wallet.getBalance() + amount);
        walletRepo.save(wallet);
        
        txnRepo.save(new Transaction(null, null, walletId, amount, TransactionType.DEPOSIT, null));
        ledgerRepo.save(new LedgerEntry(null, walletId, amount, EntryType.CREDIT, null));
    }

    @Transactional
    public void withdraw(Long walletId, Long amount)
            throws InvalidAmountException, InsufficientAmountException, WalletNotActiveException {
        Wallet wallet = walletRepo.findById(walletId).orElse(null);
        if (wallet == null) throw new IllegalArgumentException("Wallet " + walletId + " not found");
        if (wallet.getStatus() != WalletStatus.ACTIVE) throw new WalletNotActiveException("Wallet not active");
        if (amount <= 0) throw new InvalidAmountException("Amount must be greater than 0");
        if (getLedgerDerivedBalance(walletId) < amount) throw new InsufficientAmountException("Insufficient balance");

        wallet.setBalance(wallet.getBalance() - amount);
        walletRepo.save(wallet);
        
        txnRepo.save(new Transaction(null, walletId, null, amount, TransactionType.WITHDRAW, null));
        ledgerRepo.save(new LedgerEntry(null, walletId, amount, EntryType.DEBIT, null));
    }

    public List<Wallet> getAllWallets() {
        return walletRepo.findAll();
    }
}
