package com.distributed.payment_engine.controller;

import com.distributed.payment_engine.service.ReconciliationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Reconciliation Controller — Financial Audit API.
 *
 * Exposes an endpoint that runs a full reconciliation of the immutable ledger.
 * This is the endpoint you call during the interview demo to prove
 * the system is financially correct.
 *
 * GET /api/v1/reconciliation → Returns a full integrity report
 *
 * Example response:
 * {
 *   "overallStatus": "HEALTHY",
 *   "doubleEntryBalance": { "passed": true, "totalDebits": 1500, "totalCredits": 1500 },
 *   "walletBalanceVerification": { "passed": true, "walletsVerified": 5 },
 *   "orphanDetection": { "passed": true, "orphanCount": 0 }
 * }
 */
@RestController
@RequestMapping("/api/v1/reconciliation")
public class ReconciliationController {

    @Autowired
    private ReconciliationService reconciliationService;

    /**
     * Run full ledger reconciliation.
     *
     * In production, this would be behind admin auth and rate-limited.
     * For interview demo, it's open so you can call it after every test.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> reconcile() {
        Map<String, Object> report = reconciliationService.reconcile();

        String status = (String) report.get("overallStatus");
        if ("HEALTHY".equals(status)) {
            return ResponseEntity.ok(report);
        } else {
            // 409 Conflict signals that data integrity is compromised
            return ResponseEntity.status(409).body(report);
        }
    }
}
