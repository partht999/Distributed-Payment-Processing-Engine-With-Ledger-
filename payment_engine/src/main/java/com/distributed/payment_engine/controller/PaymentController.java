package com.distributed.payment_engine.controller;

import com.distributed.payment_engine.exception.ResourceNotFoundException;
import com.distributed.payment_engine.model.dto.CreatePaymentRequest;
import com.distributed.payment_engine.model.dto.PaymentResponse;
import com.distributed.payment_engine.model.entity.Payment;
import com.distributed.payment_engine.model.enums.PaymentType;
import com.distributed.payment_engine.service.PaymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    @Autowired
    private PaymentService paymentService;

    // POST /api/v1/payments — Create a new payment
    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(@Valid @RequestBody CreatePaymentRequest request) throws Exception {
        String idempotencyKey = request.getIdempotencyKey() != null
                ? request.getIdempotencyKey()
                : "key-" + System.nanoTime();

        String typeStr = request.getPaymentType() != null ? request.getPaymentType() : "P2P";
        PaymentType type = PaymentType.valueOf(typeStr.toUpperCase());

        Payment payment = paymentService.createPayment(
                request.getFromWalletId(), request.getToWalletId(), request.getAmount(),
                idempotencyKey, request.getMerchantOrderId(), type);

        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentResponse.from(payment));
    }

    // GET /api/v1/payments/{id}
    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable Long id) {
        Payment payment = paymentService.getPayment(id);
        if (payment == null) {
            throw new ResourceNotFoundException("Payment " + id + " not found");
        }
        return ResponseEntity.ok(PaymentResponse.from(payment));
    }

    // GET /api/v1/payments — List all payments
    @GetMapping
    public ResponseEntity<List<PaymentResponse>> listPayments() {
        List<PaymentResponse> payments = paymentService.getAllPayments().stream()
                .map(PaymentResponse::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(payments);
    }

    // POST /api/v1/payments/{id}/process
    @PostMapping("/{id}/process")
    public ResponseEntity<PaymentResponse> processPayment(@PathVariable Long id) throws Exception {
        Payment payment = paymentService.processPayment(id);
        return ResponseEntity.ok(PaymentResponse.from(payment));
    }

    // POST /api/v1/payments/{id}/authorize
    @PostMapping("/{id}/authorize")
    public ResponseEntity<PaymentResponse> authorizePayment(@PathVariable Long id) {
        Payment payment = paymentService.authorizePayment(id);
        return ResponseEntity.ok(PaymentResponse.from(payment));
    }

    // POST /api/v1/payments/{id}/capture
    @PostMapping("/{id}/capture")
    public ResponseEntity<PaymentResponse> capturePayment(@PathVariable Long id) throws Exception {
        Payment payment = paymentService.capturePayment(id);
        return ResponseEntity.ok(PaymentResponse.from(payment));
    }

    // POST /api/v1/payments/{id}/refund
    @PostMapping("/{id}/refund")
    public ResponseEntity<PaymentResponse> refundPayment(@PathVariable Long id) throws Exception {
        Payment payment = paymentService.refundPayment(id);
        return ResponseEntity.ok(PaymentResponse.from(payment));
    }

    // POST /api/v1/payments/{id}/reverse
    @PostMapping("/{id}/reverse")
    public ResponseEntity<PaymentResponse> reversePayment(@PathVariable Long id) {
        Payment payment = paymentService.reversePayment(id);
        return ResponseEntity.ok(PaymentResponse.from(payment));
    }
}