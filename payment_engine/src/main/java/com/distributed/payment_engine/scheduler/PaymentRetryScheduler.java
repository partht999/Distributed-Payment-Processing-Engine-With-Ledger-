package com.distributed.payment_engine.scheduler;

import com.distributed.payment_engine.model.entity.Payment;
import com.distributed.payment_engine.model.enums.PaymentStatus;
import com.distributed.payment_engine.repository.PaymentRepository;
import com.distributed.payment_engine.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** Scheduled Retry Job
 *
 * This component runs automatically every 30 seconds (configurable).
 * It scans the database for payments stuck in RETRYING status
 * and attempts to re-process them.
 *
 * This simulates how real payment processors handle retries:
 * instead of blocking the API thread, failed payments are
 * parked in a RETRYING state and picked up later by a background job.
 */
@Component
public class PaymentRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentRetryScheduler.class);

    @Autowired
    private PaymentRepository paymentRepo;

    @Autowired
    private PaymentService paymentService;

    /**
     * Runs every 30 seconds.
     * Finds all RETRYING payments and attempts to re-process each one.
     * If re-processing succeeds, the payment moves to CAPTURED.
     * If it fails again, handleRetry() increments retryCount.
     * If retryCount >= maxRetries, the payment is marked FAILED permanently.
     */
    @Scheduled(fixedRate = 30000) // 30 seconds
    public void retryFailedPayments() {
        List<Payment> retryable = paymentRepo.findByStatus(PaymentStatus.RETRYING);

        if (retryable.isEmpty()) return;

        log.info("[RetryScheduler] Found {} payments in RETRYING status. Attempting re-processing...", retryable.size());

        for (Payment payment : retryable) {
            try {
                log.info("[RetryScheduler] Retrying payment {} (attempt {}/{})",
                        payment.getPaymentId(), payment.getRetryCount() + 1, payment.getMaxRetries());

                paymentService.processPayment(payment.getPaymentId());

                log.info("[RetryScheduler] Payment {} successfully processed!", payment.getPaymentId());
            } catch (Exception e) {
                log.warn("[RetryScheduler] Payment {} retry failed: {}",
                        payment.getPaymentId(), e.getMessage());
            }
        }
    }
}
