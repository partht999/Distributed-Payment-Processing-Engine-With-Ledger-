package com.distributed.payment_engine.service;

import com.distributed.payment_engine.exception.InsufficientAmountException;
import com.distributed.payment_engine.exception.InvalidAmountException;
import com.distributed.payment_engine.exception.WalletNotActiveException;
import com.distributed.payment_engine.model.entity.Payment;
import com.distributed.payment_engine.model.enums.PaymentStatus;
import com.distributed.payment_engine.model.enums.PaymentType;
import com.distributed.payment_engine.model.event.PaymentEvent;
import com.distributed.payment_engine.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Core Payment Service.
 * REDIS FAILURE RESILIENCE
 * All Redis calls are wrapped in try-catch blocks so the system
 * gracefully degrades to DB-only mode when Redis is unavailable.
 *
 * When Redis is DOWN:
 *   - createPayment(): Skips Redis fast-path, uses DB UNIQUE constraint (slower but safe)
 *   - processPayment(): Skips processing marker + wallet lock, relies on DB-level guards
 *   - System logs WARN messages so ops team knows Redis is down
 *
 * When Redis is UP:
 *   - All 3 layers of protection are active (fastest + safest)
 *
 * PRINCIPLE: Redis is a CACHE layer, not the source of truth.
 * PostgreSQL's UNIQUE constraints and ACID transactions are the ultimate safety net.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    @Autowired
    private PaymentRepository paymentRepo;

    @Autowired
    private WalletService walletService;

    @Autowired
    private RedisIdempotencyService idempotencyService;

    @Autowired
    private RedisDistributedLockService lockService;

    @Autowired
    private RedisProcessingMarkerService processingMarkerService;

    @Autowired
    private PaymentEventPublisher eventPublisher;

    private static final long EXPIRY_TIME_MS = 120000; // 2 minutes

    @Transactional
    public Payment createPayment(Long fromWalletId, Long toWalletId, Long amount,
                                  String idempotencyKey, String merchantOrderId, PaymentType type)
            throws InvalidAmountException {

        if (amount == null || amount <= 0) throw new InvalidAmountException("Amount must be greater than 0");
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new InvalidAmountException("Idempotency key required");
        if (type == null) throw new InvalidAmountException("Payment type required");

        // ═══ REDIS IDEMPOTENCY CHECK (FAST PATH) ═══
        // Wrapped in try-catch: if Redis is down, skip to DB fallback
        try {
            if (idempotencyService.exists(idempotencyKey)) {
                log.info("[Payment] Redis says duplicate — looking up existing payment for key: {}", idempotencyKey);
                Payment existing = paymentRepo.findByIdempotencyKey(idempotencyKey);
                if (existing != null) return existing;
                // Edge case: Redis has key but DB doesn't → release and proceed
                idempotencyService.releaseKey(idempotencyKey);
            }
        } catch (Exception e) {
            // ═══ REDIS DOWN — graceful degradation ═══
            log.warn("[Payment] Redis unavailable for idempotency check — falling back to DB-only. Error: {}", e.getMessage());
            // Continue to DB fallback below — the system still works, just slower
        }

        // Step 2: DB fallback — still check PostgreSQL as the ultimate safety net
        Payment existing = paymentRepo.findByIdempotencyKey(idempotencyKey);
        if (existing != null) return existing;

        if (type == PaymentType.MERCHANT && merchantOrderId != null && !merchantOrderId.isBlank()) {
            Payment byOrder = paymentRepo.findByMerchantOrderId(merchantOrderId);
            if (byOrder != null) {
                PaymentStatus s = byOrder.getStatus();
                if (s == PaymentStatus.CREATED || s == PaymentStatus.AUTHORIZED ||
                    s == PaymentStatus.RETRYING || s == PaymentStatus.CAPTURED) {
                    return byOrder;
                }
            }
        }

        // Step 3: Create the payment in PostgreSQL
        Payment payment = new Payment(null, fromWalletId, toWalletId, amount,
                PaymentStatus.CREATED, idempotencyKey, merchantOrderId, type);
        try {
            paymentRepo.save(payment);

            // Step 4: Claim the key in Redis AFTER successful DB write
            // ═══ Wrapped — if Redis is down, payment is still created ═══
            try {
                idempotencyService.claimKey(idempotencyKey, payment.getPaymentId());
            } catch (Exception e) {
                log.warn("[Payment] Redis unavailable for idempotency claim — payment {} created in DB only. Error: {}",
                        payment.getPaymentId(), e.getMessage());
                // Payment is safely in PostgreSQL. Redis will catch up when it's back.
            }

            log.info("[Payment] Created payment {} with idempotency key: {}", payment.getPaymentId(), idempotencyKey);

            // ═══ Publish PAYMENT_CREATED event ═══
            publishEventSafely(PaymentEvent.created(
                    payment.getPaymentId(), fromWalletId, toWalletId,
                    amount, type, idempotencyKey));

            return payment;
        } catch (DataIntegrityViolationException e) {
            // The UNIQUE constraint on idempotency_key rejected this insert.
            Payment alreadySaved = paymentRepo.findByIdempotencyKey(idempotencyKey);
            if (alreadySaved != null) {
                try {
                    idempotencyService.claimKey(idempotencyKey, alreadySaved.getPaymentId());
                } catch (Exception redisEx) {
                    log.warn("[Payment] Redis unavailable for claim after duplicate. Error: {}", redisEx.getMessage());
                }
                return alreadySaved;
            }
            throw e;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Payment processPayment(Long paymentId)
            throws InvalidAmountException, InsufficientAmountException, WalletNotActiveException {

        // ═══ PROCESSING MARKER ═══
        // If Redis is down, skip the marker and proceed (DB guards are still active)
        boolean markerSet = false;
        try {
            if (!processingMarkerService.markProcessing(paymentId)) {
                log.warn("[Payment] Payment {} is already being processed by another thread/server", paymentId);
                throw new IllegalStateException(
                        "Payment " + paymentId + " is currently being processed. Please wait.");
            }
            markerSet = true;
        } catch (IllegalStateException e) {
            // This is the "already processing" exception — rethrow it
            throw e;
        } catch (Exception e) {
            // ═══ Redis down — skip processing marker ═══
            log.warn("[Payment] Redis unavailable for processing marker — proceeding without marker. Error: {}", e.getMessage());
            // Continue without marker. DB status guards still protect us.
        }

        try {
            Payment payment = paymentRepo.findById(paymentId).orElse(null);
            if (payment == null) throw new IllegalArgumentException("Payment " + paymentId + " not found");

            // === Strict re-processing safety guards ===
            PaymentStatus currentStatus = payment.getStatus();

            // Already completed — return idempotently (no double-charge)
            if (currentStatus == PaymentStatus.CAPTURED) return payment;
            if (currentStatus == PaymentStatus.AUTHORIZED) return payment;

            // Terminal states — cannot be re-processed
            if (currentStatus == PaymentStatus.FAILED)
                throw new IllegalStateException("Payment " + paymentId + " has permanently failed. Create a new payment.");
            if (currentStatus == PaymentStatus.REFUNDED)
                throw new IllegalStateException("Payment " + paymentId + " was refunded. Cannot re-process.");
            if (currentStatus == PaymentStatus.REVERSED)
                throw new IllegalStateException("Payment " + paymentId + " was reversed. Cannot re-process.");

            // Expiry check
            if (currentStatus == PaymentStatus.EXPIRED) throw new IllegalStateException("Payment expired");
            if (isExpired(payment)) {
                payment.setStatus(PaymentStatus.EXPIRED);
                paymentRepo.save(payment);
                throw new IllegalStateException("Payment expired");
            }

            // ═══ DISTRIBUTED LOCK ═══
            // If Redis is down, skip the lock and proceed (DB transaction isolation helps)
            RedisDistributedLockService.LockHandle walletLock = null;
            try {
                walletLock = lockService.tryLock(payment.getFromWalletId());
                if (walletLock == null) {
                    log.warn("[Payment] Cannot process payment {} — wallet {} is locked by another server",
                            paymentId, payment.getFromWalletId());
                    throw new IllegalStateException(
                            "Wallet " + payment.getFromWalletId() + " is currently being processed. Try again shortly.");
                }
            } catch (IllegalStateException e) {
                // This is the "wallet locked" exception — rethrow it
                throw e;
            } catch (Exception e) {
                // ═══ Redis down — skip wallet lock ═══
                log.warn("[Payment] Redis unavailable for wallet lock — proceeding without lock. Error: {}", e.getMessage());
                // Continue without lock. PostgreSQL's @Transactional provides some isolation.
                // Not as strong as Redis lock, but payments still work.
            }

            try {
                PaymentStatus previousStatus = payment.getStatus();

                // Only CREATED or RETRYING reach here — safe to process
                if (payment.getPaymentType() == PaymentType.P2P) {
                    try {
                        boolean success = walletService.transfer(
                                payment.getFromWalletId(), payment.getToWalletId(),
                                payment.getAmount(), payment.getPaymentId());
                        if (success) {
                            payment.setStatus(PaymentStatus.CAPTURED);
                        } else {
                            handleRetry(payment);
                        }
                    } catch (InvalidAmountException | InsufficientAmountException | WalletNotActiveException e) {
                        handleRetry(payment);
                        paymentRepo.save(payment);

                        // ═══ Publish RETRYING or FAILED event ═══
                        publishRetryOrFailEvent(payment, previousStatus);

                        throw e;
                    }
                } else if (payment.getPaymentType() == PaymentType.MERCHANT) {
                    payment.setStatus(PaymentStatus.AUTHORIZED);
                }

                paymentRepo.save(payment);

                // ═══ Publish event based on new status ═══
                publishStatusChangeEvent(payment, previousStatus);

                return payment;
            } finally {
                // ═══ Safe release — only if lock was acquired ═══
                if (walletLock != null) {
                    try {
                        lockService.releaseLock(walletLock);
                    } catch (Exception e) {
                        log.warn("[Payment] Redis unavailable for lock release — lock will auto-expire via TTL. Error: {}", e.getMessage());
                    }
                }
            }
        } finally {
            // ═══ 55: Clear marker — only if it was set ═══
            if (markerSet) {
                try {
                    processingMarkerService.clearMarker(paymentId);
                } catch (Exception e) {
                    log.warn("[Payment] Redis unavailable for marker clear — marker will auto-expire via TTL. Error: {}", e.getMessage());
                }
            }
        }
    }

    @Transactional
    public Payment authorizePayment(Long paymentId) {
        Payment payment = paymentRepo.findById(paymentId).orElse(null);
        if (payment == null) throw new IllegalArgumentException("Payment " + paymentId + " not found");
        if (payment.getPaymentType() != PaymentType.MERCHANT)
            throw new IllegalStateException("Only merchant payments can be authorized");
        payment.setStatus(PaymentStatus.AUTHORIZED);
        paymentRepo.save(payment);

        // ═══ Publish PAYMENT_AUTHORIZED event ═══
        publishEventSafely(PaymentEvent.authorized(
                payment.getPaymentId(), payment.getFromWalletId(), payment.getToWalletId(),
                payment.getAmount(), payment.getPaymentType(), payment.getIdempotencyKey(),
                PaymentStatus.CREATED));

        return payment;
    }

    @Transactional(rollbackFor = Exception.class)
    public Payment capturePayment(Long paymentId)
            throws InvalidAmountException, InsufficientAmountException, WalletNotActiveException {
        Payment payment = paymentRepo.findById(paymentId).orElse(null);
        if (payment == null) throw new IllegalArgumentException("Payment " + paymentId + " not found");
        if (payment.getStatus() != PaymentStatus.AUTHORIZED)
            throw new IllegalStateException("Must authorize first");

        boolean success = walletService.transfer(
                payment.getFromWalletId(), payment.getToWalletId(),
                payment.getAmount(), payment.getPaymentId());
        if (success) {
            payment.setStatus(PaymentStatus.CAPTURED);
        }
        paymentRepo.save(payment);

        // ═══ Publish PAYMENT_CAPTURED event ═══
        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            publishEventSafely(PaymentEvent.captured(
                    payment.getPaymentId(), payment.getFromWalletId(), payment.getToWalletId(),
                    payment.getAmount(), payment.getPaymentType(), payment.getIdempotencyKey(),
                    PaymentStatus.AUTHORIZED));
        }
        return payment;
    }

    @Transactional(rollbackFor = Exception.class)
    public Payment refundPayment(Long paymentId)
            throws InvalidAmountException, InsufficientAmountException, WalletNotActiveException {
        Payment payment = paymentRepo.findById(paymentId).orElse(null);
        if (payment == null) throw new IllegalArgumentException("Payment " + paymentId + " not found");
        if (payment.getStatus() != PaymentStatus.CAPTURED)
            throw new IllegalStateException("Only captured payments can be refunded");

        boolean success = walletService.transfer(
                payment.getToWalletId(), payment.getFromWalletId(),
                payment.getAmount(), payment.getPaymentId());
        if (success) {
            payment.setStatus(PaymentStatus.REFUNDED);
        }
        paymentRepo.save(payment);

        // ═══ Publish PAYMENT_REFUNDED event ═══
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            publishEventSafely(PaymentEvent.refunded(
                    payment.getPaymentId(), payment.getFromWalletId(), payment.getToWalletId(),
                    payment.getAmount(), payment.getPaymentType(), payment.getIdempotencyKey()));
        }
        return payment;
    }

    @Transactional
    public Payment reversePayment(Long paymentId) {
        Payment payment = paymentRepo.findById(paymentId).orElse(null);
        if (payment == null) throw new IllegalArgumentException("Payment " + paymentId + " not found");
        if (payment.getStatus() != PaymentStatus.AUTHORIZED)
            throw new IllegalStateException("Only authorized payments can be reversed");
        payment.setStatus(PaymentStatus.REVERSED);
        paymentRepo.save(payment);

        // ═══ Publish PAYMENT_REVERSED event ═══
        publishEventSafely(PaymentEvent.reversed(
                payment.getPaymentId(), payment.getFromWalletId(), payment.getToWalletId(),
                payment.getAmount(), payment.getPaymentType(), payment.getIdempotencyKey()));

        return payment;
    }

    public Payment getPayment(Long paymentId) {
        return paymentRepo.findById(paymentId).orElse(null);
    }

    public List<Payment> getAllPayments() {
        return paymentRepo.findAll();
    }

    private boolean isExpired(Payment payment) {
        return (System.currentTimeMillis() - payment.getCreatedAt()) > EXPIRY_TIME_MS;
    }

    private void handleRetry(Payment payment) {
        payment.incrementRetryCount();
        if (payment.getRetryCount() < payment.getMaxRetries()) {
            payment.setStatus(PaymentStatus.RETRYING);
        } else {
            payment.setStatus(PaymentStatus.FAILED);
        }
    }

    // ═══════════════════════════════════════════════════════
    // Event publishing helper methods
    // ═══════════════════════════════════════════════════════

    /**
     * Safely publish an event — never let event publishing crash a payment.
     * If publishing fails, we log a warning but the payment still succeeds.
     */
    private void publishEventSafely(PaymentEvent event) {
        try {
            eventPublisher.publish(event);
        } catch (Exception e) {
            log.warn("[Payment] Failed to publish event {} for payment {}. Error: {}",
                    event.getEventType(), event.getPaymentId(), e.getMessage());
        }
    }

    /**
     * Publish the appropriate event based on the payment's new status after processPayment.
     */
    private void publishStatusChangeEvent(Payment payment, PaymentStatus previousStatus) {
        PaymentEvent event = switch (payment.getStatus()) {
            case CAPTURED -> PaymentEvent.captured(
                    payment.getPaymentId(), payment.getFromWalletId(), payment.getToWalletId(),
                    payment.getAmount(), payment.getPaymentType(), payment.getIdempotencyKey(),
                    previousStatus);
            case AUTHORIZED -> PaymentEvent.authorized(
                    payment.getPaymentId(), payment.getFromWalletId(), payment.getToWalletId(),
                    payment.getAmount(), payment.getPaymentType(), payment.getIdempotencyKey(),
                    previousStatus);
            case RETRYING -> PaymentEvent.retrying(
                    payment.getPaymentId(), payment.getFromWalletId(), payment.getToWalletId(),
                    payment.getAmount(), payment.getPaymentType(), payment.getIdempotencyKey(),
                    previousStatus);
            case FAILED -> PaymentEvent.failed(
                    payment.getPaymentId(), payment.getFromWalletId(), payment.getToWalletId(),
                    payment.getAmount(), payment.getPaymentType(), payment.getIdempotencyKey(),
                    previousStatus);
            default -> null;
        };
        if (event != null) {
            publishEventSafely(event);
        }
    }

    /**
     * Publish retry or fail event when processPayment catches a business exception.
     */
    private void publishRetryOrFailEvent(Payment payment, PaymentStatus previousStatus) {
        if (payment.getStatus() == PaymentStatus.RETRYING) {
            publishEventSafely(PaymentEvent.retrying(
                    payment.getPaymentId(), payment.getFromWalletId(), payment.getToWalletId(),
                    payment.getAmount(), payment.getPaymentType(), payment.getIdempotencyKey(),
                    previousStatus));
        } else if (payment.getStatus() == PaymentStatus.FAILED) {
            publishEventSafely(PaymentEvent.failed(
                    payment.getPaymentId(), payment.getFromWalletId(), payment.getToWalletId(),
                    payment.getAmount(), payment.getPaymentType(), payment.getIdempotencyKey(),
                    previousStatus));
        }
    }
}
