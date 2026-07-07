package com.distributed.payment_engine.service;

import com.distributed.payment_engine.model.entity.LedgerEntry;
import com.distributed.payment_engine.model.entity.Wallet;
import com.distributed.payment_engine.model.enums.EntryType;
import com.distributed.payment_engine.repository.LedgerRepository;
import com.distributed.payment_engine.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Reconciliation Service — Financial Integrity Verification.
 *
 * In any payment system, the #1 question auditors and interviewers ask is:
 * "How do you know your ledger is correct?"
 *
 * This service answers that question by running three verification checks:
 *
 * CHECK 1: DOUBLE-ENTRY BALANCE
 *   Total DEBITs across all wallets must equal total CREDITs.
 *   If they don't balance, money was created or destroyed — a critical bug.
 *
 * CHECK 2: WALLET BALANCE vs LEDGER TRUTH
 *   The wallet.balance column is a cached convenience value.
 *   The real balance is SUM(CREDITs) - SUM(DEBITs) from ledger_entries.
 *   If they disagree, we have a cache coherence bug.
 *
 * CHECK 3: ORPHAN DETECTION
 *   Every ledger entry should reference a valid walletId.
 *   Orphans indicate referential integrity violations.
 *
 * HOW STRIPE DOES IT:
 *   Stripe runs reconciliation as a daily batch job. If a mismatch is found,
 *   the system flags the discrepancy and halts payouts for that merchant
 *   until a human reviews it. We implement the same principle as an API
 *   that can be called on-demand or scheduled.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    @Autowired
    private LedgerRepository ledgerRepo;

    @Autowired
    private WalletRepository walletRepo;

    /**
     * Run a full reconciliation and return a detailed report.
     *
     * This is the method you demo to the interviewer. It proves your
     * system is financially correct, not just functionally correct.
     */
    public Map<String, Object> reconcile() {
        log.info("[Reconciliation] Starting full ledger reconciliation...");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("timestamp", java.time.Instant.now().toString());

        List<LedgerEntry> allEntries = ledgerRepo.findAll();
        List<Wallet> allWallets = walletRepo.findAll();

        // ─── CHECK 1: Double-Entry Balance ───
        Map<String, Object> doubleEntryCheck = checkDoubleEntryBalance(allEntries);
        report.put("doubleEntryBalance", doubleEntryCheck);

        // ─── CHECK 2: Wallet Balance vs Ledger Truth ───
        Map<String, Object> walletCheck = checkWalletBalances(allWallets, allEntries);
        report.put("walletBalanceVerification", walletCheck);

        // ─── CHECK 3: Orphan Detection ───
        Map<String, Object> orphanCheck = checkOrphans(allEntries, allWallets);
        report.put("orphanDetection", orphanCheck);

        // ─── Summary ───
        boolean allPassed = (boolean) doubleEntryCheck.get("passed")
                && (boolean) walletCheck.get("passed")
                && (boolean) orphanCheck.get("passed");

        report.put("overallStatus", allPassed ? "HEALTHY" : "DISCREPANCY_FOUND");
        report.put("totalLedgerEntries", allEntries.size());
        report.put("totalWallets", allWallets.size());

        log.info("[Reconciliation] Complete. Status: {}", allPassed ? "HEALTHY" : "DISCREPANCY_FOUND");
        return report;
    }

    /**
     * CHECK 1: Verify that total DEBITs equal total CREDITs across the entire system.
     *
     * In double-entry bookkeeping, money is never created or destroyed.
     * Every DEBIT from one wallet is balanced by a CREDIT to another.
     * If totalDebits != totalCredits, there's a critical accounting bug.
     *
     * EXCEPTION: Initial wallet deposits (no fromWallet) create CREDITs
     * without a matching DEBIT. We separate these as "system credits"
     * and exclude them from the balance check.
     */
    private Map<String, Object> checkDoubleEntryBalance(List<LedgerEntry> entries) {
        long totalDebits = 0;
        long totalCredits = 0;
        long systemCredits = 0; // Initial deposits with no matching debit

        for (LedgerEntry entry : entries) {
            if (entry.getEntryType() == EntryType.DEBIT) {
                totalDebits += entry.getAmount();
            } else if (entry.getEntryType() == EntryType.CREDIT) {
                if (entry.getPaymentId() == null) {
                    // System credit (initial deposit or standalone deposit)
                    systemCredits += entry.getAmount();
                } else {
                    totalCredits += entry.getAmount();
                }
            }
        }

        boolean balanced = totalDebits == totalCredits;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", balanced);
        result.put("totalDebits", totalDebits);
        result.put("totalCredits", totalCredits);
        result.put("systemCredits", systemCredits);
        result.put("netDifference", totalCredits - totalDebits);

        if (!balanced) {
            log.error("[Reconciliation] DOUBLE-ENTRY MISMATCH! Debits={} Credits={} Diff={}",
                    totalDebits, totalCredits, totalCredits - totalDebits);
        }

        return result;
    }

    /**
     * CHECK 2: Verify each wallet's cached balance matches its ledger-derived balance.
     *
     * wallet.balance is a convenience column updated on every transaction.
     * The source of truth is: SUM(CREDITs) - SUM(DEBITs) from ledger_entries.
     *
     * If they disagree, one of these happened:
     *   - A transaction updated the wallet but failed to write the ledger entry
     *   - A race condition corrupted the cached balance
     *   - Someone manually edited the wallet table (never do this!)
     */
    private Map<String, Object> checkWalletBalances(List<Wallet> wallets, List<LedgerEntry> entries) {
        // Build a map: walletId → ledger-derived balance
        Map<Long, Long> ledgerBalances = new HashMap<>();
        for (LedgerEntry entry : entries) {
            Long walletId = entry.getWalletId();
            long current = ledgerBalances.getOrDefault(walletId, 0L);
            if (entry.getEntryType() == EntryType.CREDIT) {
                ledgerBalances.put(walletId, current + entry.getAmount());
            } else {
                ledgerBalances.put(walletId, current - entry.getAmount());
            }
        }

        List<Map<String, Object>> mismatches = new ArrayList<>();
        int verified = 0;

        for (Wallet wallet : wallets) {
            Long cachedBalance = wallet.getBalance();
            Long ledgerBalance = ledgerBalances.getOrDefault(wallet.getWalletId(), 0L);

            if (!cachedBalance.equals(ledgerBalance)) {
                Map<String, Object> mismatch = new LinkedHashMap<>();
                mismatch.put("walletId", wallet.getWalletId());
                mismatch.put("cachedBalance", cachedBalance);
                mismatch.put("ledgerBalance", ledgerBalance);
                mismatch.put("difference", cachedBalance - ledgerBalance);
                mismatches.add(mismatch);

                log.error("[Reconciliation] BALANCE MISMATCH for wallet {}! Cached={} Ledger={}",
                        wallet.getWalletId(), cachedBalance, ledgerBalance);
            } else {
                verified++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", mismatches.isEmpty());
        result.put("walletsVerified", verified);
        result.put("walletsWithMismatch", mismatches.size());
        if (!mismatches.isEmpty()) {
            result.put("mismatches", mismatches);
        }

        return result;
    }

    /**
     * CHECK 3: Detect orphan ledger entries that reference non-existent wallets.
     *
     * Every ledger entry should have a corresponding wallet.
     * Orphans indicate data integrity issues (e.g., wallet deleted but ledger entries remain).
     */
    private Map<String, Object> checkOrphans(List<LedgerEntry> entries, List<Wallet> wallets) {
        Set<Long> walletIds = new HashSet<>();
        for (Wallet w : wallets) {
            walletIds.add(w.getWalletId());
        }

        List<Long> orphanEntryIds = new ArrayList<>();
        for (LedgerEntry entry : entries) {
            if (!walletIds.contains(entry.getWalletId())) {
                orphanEntryIds.add(entry.getEntryId());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", orphanEntryIds.isEmpty());
        result.put("orphanCount", orphanEntryIds.size());
        if (!orphanEntryIds.isEmpty()) {
            result.put("orphanEntryIds", orphanEntryIds);
        }

        return result;
    }
}
